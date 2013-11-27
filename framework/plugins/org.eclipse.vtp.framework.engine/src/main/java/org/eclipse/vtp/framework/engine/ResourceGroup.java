/*--------------------------------------------------------------------------
 * Copyright (c) 2004, 2006-2007 OpenMethods, LLC
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Trip Gilman (OpenMethods), Lonnie G. Pryor (OpenMethods)
 *    - initial API and implementation
 -------------------------------------------------------------------------*/
package org.eclipse.vtp.framework.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.vtp.framework.interactions.core.media.IResourceManager;
import org.eclipse.vtp.framework.interactions.voice.services.ExternalServer;
import org.eclipse.vtp.framework.interactions.voice.services.ExternalServerManager;
import org.eclipse.vtp.framework.interactions.voice.services.ExternalServerManagerListener;
import org.osgi.framework.Bundle;

/**
 * A group of public resources.
 * 
 * @author Lonnie Pryor
 */
public class ResourceGroup implements IResourceManager, ExternalServerManagerListener
{
	/** The bundle to load from. */
	private final Bundle bundle;
	/** The base path to publish. */
	private final String path;
	private Object lock = new Object();
	private HashSet<String> index = new HashSet<String>();

	/**
	 * Creates a new ResourceGroup.
	 * 
	 * @param bundle The bundle to load from.
	 * @param path The base path to publish.
	 */
	public ResourceGroup(Bundle bundle, String path)
	{
		ExternalServerManager.getInstance().addListener(this);
		this.bundle = bundle;
		if (!path.startsWith("/")) //$NON-NLS-1$
			path = "/" + path; //$NON-NLS-2$
		this.path = path;
		URL indexURL = bundle.getResource("files.index");
		if(indexURL != null)
		{
			try
			{
				BufferedReader br = new BufferedReader(new InputStreamReader(indexURL.openConnection().getInputStream()));
				String line = br.readLine();
				while(line != null)
				{
					index.add(line);
					line = br.readLine();
				}
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
		Thread t = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					while(true)
					{
						HashSet<String> localIndex = new HashSet<String>();
						ExternalServerManager.Logging logging = ExternalServerManager.getInstance().getLogging();
						System.out.println(logging);
						List<ExternalServer> locations = ExternalServerManager.getInstance().getLocations();
						System.out.println(locations);
						System.out.println(locations.size());
						if(locations.size() > 0)
						{
							boolean connected = false;
							for(ExternalServer server : locations)
							{
								String location = server.getLocation();
								if(!location.endsWith("/"))
									location = location + "/";
								location = location + ResourceGroup.this.bundle.getHeaders().get("Bundle-Name") + "/";
								if(logging == ExternalServerManager.Logging.ALWAYS)
									System.out.println("Attempting to load index from: " + location);
								try
								{
									URL indexURL = new URL(location);
									BufferedReader br = new BufferedReader(new InputStreamReader(indexURL.openConnection().getInputStream()));
									String line = br.readLine();
									while(line != null)
									{
										if(logging == ExternalServerManager.Logging.ALWAYS)
											System.out.println(ResourceGroup.this.bundle.getHeaders().get("Bundle-Name") + " " + line);
										localIndex.add(line);
										line = br.readLine();
									}
									br.close();
									index = localIndex;
									connected = true;
									server.setStatus(true);
									break;
								}
								catch (Exception e)
								{
									switch(logging)
									{
										case FIRSTFAILURE:
											if(!server.lastStatus())
												break;
										case ALWAYS:
											System.out.println("Unable to connect to external media server @ " + location);
											e.printStackTrace();
									}
									server.setStatus(false);
								}
							}
							if(!connected && logging != ExternalServerManager.Logging.NONE)
								System.out.println("Unable to load index for " + ResourceGroup.this.bundle.getHeaders().get("Bundle-Name") + " from any external media servers");
						}
						try
						{
							synchronized(lock)
							{
								lock.wait(30000);
							}
						}
						catch(Exception ex)
						{
							
						}
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
		}, bundle.getSymbolicName() + "-index");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Returns the requested resource.
	 * 
	 * @param fullResourcePath The path of the resource to return.
	 * @return The requested resource.
	 */
	public URL getResource(String fullResourcePath)
	{
		if(!fullResourcePath.startsWith("/"))
			fullResourcePath = "/" + fullResourcePath;
		System.out.println("resolving resource: " + path + fullResourcePath);
		URL ret = bundle.getEntry(path + fullResourcePath);
//		System.out.println("location: " + ret);
		return ret;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.vtp.framework.interactions.core.media.IResourceManager#
	 *      listResources(java.lang.String)
	 */
	public String[] listResources(String fullDirectoryPath)
	{
		if(!fullDirectoryPath.startsWith("/"))
			fullDirectoryPath = "/" + fullDirectoryPath;
		LinkedList<String> list = new LinkedList<String>();
		for (Enumeration<String> e = bundle.getEntryPaths(path + fullDirectoryPath); e != null
				&& e.hasMoreElements();)
			list.add(e.nextElement());
		return list.toArray(new String[list.size()]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.vtp.framework.interactions.core.media.IResourceManager#
	 *      isDirectoryResource(java.lang.String)
	 */
	public boolean isDirectoryResource(String fullDirectoryPath)
	{
		return fullDirectoryPath.endsWith("/"); //$NON-NLS-1$
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.vtp.framework.interactions.core.media.IResourceManager#
	 *      isFileResource(java.lang.String)
	 */
	public boolean isFileResource(String fullFilePath)
	{
		if(fullFilePath.startsWith("/"))
			fullFilePath = fullFilePath.substring(1);
		int slashIndex = fullFilePath.indexOf('/');
		if(slashIndex >= 0)
		{
			String prefix = fullFilePath.substring(0, slashIndex);
			String libraryFile = "/" + prefix + "/.library";
			if(!index.contains(libraryFile) && getResource(libraryFile) == null)
				fullFilePath = "Default/" + fullFilePath;
		}
		else
			fullFilePath = "Default/" + fullFilePath;
		fullFilePath = "/" + fullFilePath;
//		System.out.println("Checking existence of " + fullFilePath + ": " + Boolean.toString(!isDirectoryResource(fullFilePath)
//		&& (index.contains(fullFilePath) || getResource(fullFilePath) != null)));
		return !isDirectoryResource(fullFilePath)
		&& (index.contains(fullFilePath) || getResource(fullFilePath) != null);
	}

	@Override
	public boolean hasMediaLibrary(String libraryId)
	{
		String libraryPath = "/" + libraryId + "/.library";
		return index.contains(libraryPath) || getResource(libraryPath) != null;
	}

	@Override
	public void locationsChanged()
	{
		synchronized(lock)
		{
			lock.notifyAll();
		}
	}
}
