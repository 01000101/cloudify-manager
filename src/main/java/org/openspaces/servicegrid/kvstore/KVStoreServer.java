/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.openspaces.servicegrid.kvstore;

import javax.servlet.Servlet;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.google.common.base.Throwables;
import com.sun.jersey.spi.container.servlet.ServletContainer;

public class KVStoreServer {

	private static Server server;
	private static ServletContainer servletContainer;
	
	public void start(int port) {
		server = new Server(port);
	    servletContainer = new ServletContainer(new KVStoreResourceConfig());
		server.setHandler(createWebAppContext(servletContainer));
		try {
			server.start();
		}
		catch (Exception e) {
			throw Throwables.propagate(e);
		}
	}
	
	public void reload() {
		servletContainer.reload();
	}
	
	public void stop() {
		try {
			server.stop();
		}
		catch (Exception e) {
			throw Throwables.propagate(e);
		}
	}
	
	private static ServletContextHandler createWebAppContext(Servlet servlet) {
		ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
		handler.setContextPath("/");
		ServletHolder servletHolder = new ServletHolder(servlet);
		servletHolder.setInitParameter("com.sun.jersey.config.property.packages", KVStoreServlet.class.getPackage().getName());
		servletHolder.setInitParameter("cacheControl","max-age=0,public");
		handler.addServlet(servletHolder, "/*");
		return handler;
	}
}
