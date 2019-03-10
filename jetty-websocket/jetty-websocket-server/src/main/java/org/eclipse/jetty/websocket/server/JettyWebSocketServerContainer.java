//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.servlet.ServletContext;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;

public class JettyWebSocketServerContainer extends ContainerLifeCycle implements WebSocketContainer, LifeCycle.Listener
{

    public static JettyWebSocketServerContainer ensureContainer(ServletContext servletContext)
    {
        ContextHandler contextHandler = ServletContextHandler.getServletContextHandler(servletContext, "Javax Websocket");
        if (contextHandler.getServer() == null)
            throw new IllegalStateException("Server has not been set on the ServletContextHandler");

        JettyWebSocketServerContainer container = contextHandler.getBean(JettyWebSocketServerContainer.class);
        if (container==null)
        {
            // Find Pre-Existing executor
            Executor executor = (Executor)servletContext.getAttribute("org.eclipse.jetty.server.Executor");
            if (executor == null)
                executor = contextHandler.getServer().getThreadPool();

            // Create the Jetty ServerContainer implementation
            container = new JettyWebSocketServerContainer(
                    WebSocketMapping.ensureMapping(servletContext, WebSocketMapping.DEFAULT_KEY),
                    WebSocketComponents.ensureWebSocketComponents(servletContext), executor);
            contextHandler.addManaged(container);
            contextHandler.addLifeCycleListener(container);
        }

        return container;
    }

    private final static Logger LOG = Log.getLogger(JettyWebSocketServerContainer.class);

    private final WebSocketMapping webSocketMapping;
    private final WebSocketComponents webSocketComponents;
    private final FrameHandlerFactory frameHandlerFactory;
    private final Executor executor;
    private final FrameHandler.ConfigurationCustomizer customizer = new FrameHandler.ConfigurationCustomizer();

    private final List<WebSocketSessionListener> sessionListeners = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();

    /**
     * Main entry point for {@link JettyWebSocketServletContainerInitializer}.
     * @param webSocketMapping the {@link WebSocketMapping} that this container belongs to
     * @param webSocketComponents the {@link WebSocketComponents} instance to use
     * @param executor the {@link Executor} to use
     */
    public JettyWebSocketServerContainer(WebSocketMapping webSocketMapping, WebSocketComponents webSocketComponents, Executor executor)
    {
        this.webSocketMapping = webSocketMapping;
        this.webSocketComponents = webSocketComponents;
        this.executor = executor;
        this.frameHandlerFactory = new JettyServerFrameHandlerFactory(this);

        addSessionListener(sessionTracker);
    }


    public void addMapping(String pathSpec, WebSocketCreator creator)
    {
        addMapping(WebSocketMapping.parsePathSpec(pathSpec), creator);
    }

    public void addMapping(PathSpec pathSpec, WebSocketCreator creator) throws WebSocketException
    {
        if (webSocketMapping.getMapping(pathSpec) != null)
            throw new WebSocketException("Duplicate WebSocket Mapping for PathSpec");

        webSocketMapping.addMapping(pathSpec, creator, frameHandlerFactory, customizer);
    }

    @Override
    public Executor getExecutor()
    {
        return this.executor;
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        sessionListeners.add(listener);
    }

    @Override
    public boolean removeSessionListener(WebSocketSessionListener listener)
    {
        return sessionListeners.remove(listener);
    }

    @Override
    public void notifySessionListeners(Consumer<WebSocketSessionListener> consumer)
    {
        for (WebSocketSessionListener listener : sessionListeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    @Override
    public Collection<Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }
}
