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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.QuietServletException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests the correct functioning of the AsyncContext
 * <p/>
 * tests for #371649 and #371635
 */
public class AsyncContextTest
{
    private Server _server;
    private ServletContextHandler _contextHandler;
    private LocalConnector _connector;

    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.setIdleTimeout(5000);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        _server.addConnector(_connector);

        _contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        _contextHandler.setContextPath("/ctx");
        _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/servletPath");
        _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/path with spaces/servletPath");
        _contextHandler.addServlet(new ServletHolder(new TestServlet2()), "/servletPath2");

        ServletHolder testHolder = new ServletHolder(new TestServlet());
        testHolder.setInitParameter("dispatchPath", "/test2/something%2felse");
        _contextHandler.addServlet(testHolder, "/test/*");
        _contextHandler.addServlet(new ServletHolder(new TestServlet2()), "/test2/*");

        _contextHandler.addServlet(new ServletHolder(new SelfDispatchingServlet()), "/self/*");

        _contextHandler.addServlet(new ServletHolder(new TestStartThrowServlet()), "/startthrow/*");
        _contextHandler.addServlet(new ServletHolder(new ForwardingServlet()), "/forward");
        _contextHandler.addServlet(new ServletHolder(new AsyncDispatchingServlet()), "/dispatchingServlet");
        _contextHandler.addServlet(new ServletHolder(new ExpireServlet()), "/expire/*");
        _contextHandler.addServlet(new ServletHolder(new BadExpireServlet()), "/badexpire/*");
        _contextHandler.addServlet(new ServletHolder(new ErrorServlet()), "/error/*");

        ErrorPageErrorHandler error_handler = new ErrorPageErrorHandler();
        _contextHandler.setErrorHandler(error_handler);
        error_handler.addErrorPage(500, "/error/500");
        error_handler.addErrorPage(IOException.class.getName(), "/error/IOE");

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]
            {_contextHandler, new DefaultHandler()});

        _server.setHandler(handlers);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testSimpleAsyncContext() throws Exception
    {
        String request =
            "GET /ctx/servletPath HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat(responseBody, containsString("doGet:getServletPath:/servletPath"));
        assertThat(responseBody, containsString("doGet:async:getServletPath:/servletPath"));
        assertThat(responseBody, containsString("async:run:attr:servletPath:/servletPath"));
    }

    @Test
    public void testStartThrow() throws Exception
    {
        String request =
            "GET /ctx/startthrow HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request, 10, TimeUnit.MINUTES));

        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        String responseBody = response.getContent();

        assertThat(responseBody, containsString("ERROR: /error"));
        assertThat(responseBody, containsString("PathInfo= /IOE"));
        assertThat(responseBody, containsString("EXCEPTION: org.eclipse.jetty.server.QuietServletException: java.io.IOException: Test"));
    }

    @Test
    public void testStartDispatchThrow() throws Exception
    {
        String request =
            "GET /ctx/startthrow?dispatch=true HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        String responseBody = response.getContent();

        assertThat(responseBody, containsString("ERROR: /error"));
        assertThat(responseBody, containsString("PathInfo= /IOE"));
        assertThat(responseBody, containsString("EXCEPTION: org.eclipse.jetty.server.QuietServletException: java.io.IOException: Test"));
    }

    @Test
    public void testStartCompleteThrow() throws Exception
    {
        String request = "GET /ctx/startthrow?complete=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        String responseBody = response.getContent();
        assertThat(responseBody, containsString("ERROR: /error"));
        assertThat(responseBody, containsString("PathInfo= /IOE"));
        assertThat(responseBody, containsString("EXCEPTION: org.eclipse.jetty.server.QuietServletException: java.io.IOException: Test"));
    }

    @Test
    public void testStartFlushCompleteThrow() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            String request = "GET /ctx/startthrow?flush=true&complete=true HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
            assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

            String responseBody = response.getContent();

            assertThat("error servlet", responseBody, containsString("completeBeforeThrow"));
        }
    }

    @Test
    public void testDispatchAsyncContext() throws Exception
    {
        String request = "GET /ctx/servletPath?dispatch=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();
        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath2"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath2"));
        assertThat("servlet path attr is original", responseBody, containsString("async:run:attr:servletPath:/servletPath"));
        assertThat("path info attr is correct", responseBody, containsString("async:run:attr:pathInfo:null"));
        assertThat("query string attr is correct", responseBody, containsString("async:run:attr:queryString:dispatch=true"));
        assertThat("context path attr is correct", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("request uri attr is correct", responseBody, containsString("async:run:attr:requestURI:/ctx/servletPath"));
    }

    @Test
    public void testDispatchAsyncContext_EncodedUrl() throws Exception
    {
        String request = "GET /ctx/test/hello%2fthere?dispatch=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        // initial values
        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/test2"));
        assertThat("request uri has correct encoding", responseBody, containsString("doGet:getRequestURI:/ctx/test2/something%2felse"));
        assertThat("request url has correct encoding", responseBody, containsString("doGet:getRequestURL:http://localhost/ctx/test2/something%2felse"));
        assertThat("path info has correct encoding", responseBody, containsString("doGet:getPathInfo:/something/else"));

        // async values
        assertThat("async servlet gets right path", responseBody, containsString("doGet:async:getServletPath:/test2"));
        assertThat("async request uri has correct encoding", responseBody, containsString("doGet:async:getRequestURI:/ctx/test2/something%2felse"));
        assertThat("async request url has correct encoding", responseBody, containsString("doGet:async:getRequestURL:http://localhost/ctx/test2/something%2felse"));
        assertThat("async path info has correct encoding", responseBody, containsString("doGet:async:getPathInfo:/something/else"));

        // async run attributes
        assertThat("async run attr servlet path is original", responseBody, containsString("async:run:attr:servletPath:/test"));
        assertThat("async run attr path info has correct encoding", responseBody, containsString("async:run:attr:pathInfo:/hello/there"));
        assertThat("async run attr query string", responseBody, containsString("async:run:attr:queryString:dispatch=true"));
        assertThat("async run context path", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("async run request uri has correct encoding", responseBody, containsString("async:run:attr:requestURI:/ctx/test/hello%2fthere"));
    }

    @Test
    public void testDispatchAsyncContext_SelfEncodedUrl() throws Exception
    {
        String request = "GET /ctx/self/hello%2fthere?dispatch=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet request uri initial", responseBody, containsString("doGet.REQUEST.requestURI:/ctx/self/hello%2fthere"));
        assertThat("servlet request uri async", responseBody, containsString("doGet.ASYNC.requestURI:/ctx/self/hello%2fthere"));
    }

    @Test
    public void testDispatchAsyncContextEncodedPathAndQueryString() throws Exception
    {
        String request = "GET /ctx/path%20with%20spaces/servletPath?dispatch=true&queryStringWithEncoding=space%20space HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath2"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath2"));
        assertThat("servlet path attr is original", responseBody, containsString("async:run:attr:servletPath:/path with spaces/servletPath"));
        assertThat("path info attr is correct", responseBody, containsString("async:run:attr:pathInfo:null"));
        assertThat("query string attr is correct", responseBody, containsString("async:run:attr:queryString:dispatch=true&queryStringWithEncoding=space%20space"));
        assertThat("context path attr is correct", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("request uri attr is correct", responseBody, containsString("async:run:attr:requestURI:/ctx/path%20with%20spaces/servletPath"));
    }

    @Test
    public void testSimpleWithContextAsyncContext() throws Exception
    {
        String request = "GET /ctx/servletPath HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath"));
        assertThat("async context gets right path in async", responseBody, containsString("async:run:attr:servletPath:/servletPath"));
    }

    @Test
    public void testDispatchWithContextAsyncContext() throws Exception
    {
        String request = "GET /ctx/servletPath?dispatch=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath2"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath2"));
        assertThat("servlet path attr is original", responseBody, containsString("async:run:attr:servletPath:/servletPath"));
        assertThat("path info attr is correct", responseBody, containsString("async:run:attr:pathInfo:null"));
        assertThat("query string attr is correct", responseBody, containsString("async:run:attr:queryString:dispatch=true"));
        assertThat("context path attr is correct", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("request uri attr is correct", responseBody, containsString("async:run:attr:requestURI:/ctx/servletPath"));
    }

    @Test
    public void testDispatch() throws Exception
    {
        String request =
            "GET /ctx/forward HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        String responseString = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(responseString);
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();
        assertThat("!ForwardingServlet", responseBody, containsString("Dispatched back to ForwardingServlet"));
    }

    @Test
    public void testDispatchRequestResponse() throws Exception
    {
        String request = "GET /ctx/forward?dispatchRequestResponse=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        String responseString = _connector.getResponse(request);

        HttpTester.Response response = HttpTester.parseResponse(responseString);
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("!AsyncDispatchingServlet", responseBody, containsString("Dispatched back to AsyncDispatchingServlet"));
    }

    private class ForwardingServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() == DispatcherType.ASYNC)
            {
                response.getOutputStream().print("Dispatched back to ForwardingServlet");
            }
            else
            {
                request.getRequestDispatcher("/dispatchingServlet").forward(request, response);
            }
        }
    }

    private class SelfDispatchingServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            DispatcherType dispatcherType = request.getDispatcherType();
            response.getOutputStream().print("doGet." + dispatcherType.name() + ".requestURI:" + request.getRequestURI() + "\n");

            if (dispatcherType == DispatcherType.ASYNC)
            {
                response.getOutputStream().print("Dispatched back to " + SelfDispatchingServlet.class.getSimpleName() + "\n");
            }
            else
            {
                final AsyncContext asyncContext = request.startAsync(request, response);
                new Thread(() -> asyncContext.dispatch()).start();
            }
        }
    }

    private class AsyncDispatchingServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, final HttpServletResponse response) throws ServletException, IOException
        {
            Request request = (Request)req;
            if (request.getDispatcherType() == DispatcherType.ASYNC)
            {
                response.getOutputStream().print("Dispatched back to AsyncDispatchingServlet");
            }
            else
            {
                boolean wrapped = false;
                final AsyncContext asyncContext;
                if (request.getParameter("dispatchRequestResponse") != null)
                {
                    wrapped = true;
                    asyncContext = request.startAsync(request, new Wrapper(response));
                }
                else
                {
                    asyncContext = request.startAsync();
                }

                new Thread(new DispatchingRunnable(asyncContext, wrapped)).start();
            }
        }
    }

    @Test
    public void testExpire() throws Exception
    {
        String request = "GET /ctx/expire HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        String responseBody = response.getContent();

        assertThat("error servlet", responseBody, containsString("ERROR: /error"));
    }

    @Test
    public void testBadExpire() throws Exception
    {
        String request = "GET /ctx/badexpire HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        String responseBody = response.getContent();

        assertThat("error servlet", responseBody, containsString("ERROR: /error"));
        assertThat("error servlet", responseBody, containsString("PathInfo= /500"));
        assertThat("error servlet", responseBody, containsString("EXCEPTION: java.lang.RuntimeException: TEST"));
    }

    private class DispatchingRunnable implements Runnable
    {
        private AsyncContext asyncContext;
        private boolean wrapped;

        public DispatchingRunnable(AsyncContext asyncContext, boolean wrapped)
        {
            this.asyncContext = asyncContext;
            this.wrapped = wrapped;
        }

        @Override
        public void run()
        {
            if (wrapped)
                assertTrue(asyncContext.getResponse() instanceof Wrapper);
            asyncContext.dispatch();
        }
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        _server.join();
    }

    private class ErrorServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.getOutputStream().print("ERROR: " + request.getServletPath() + "\n");
            response.getOutputStream().print("PathInfo= " + request.getPathInfo() + "\n");
            if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) != null)
                response.getOutputStream().print("EXCEPTION: " + request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) + "\n");
        }
    }

    private class ExpireServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() == DispatcherType.REQUEST)
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(100);
            }
        }
    }

    private class BadExpireServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() == DispatcherType.REQUEST)
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                        throw new RuntimeException("TEST");
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                    }

                    @Override
                    public void onComplete(AsyncEvent event) throws IOException
                    {
                    }
                });
                asyncContext.setTimeout(100);
            }
        }
    }

    private class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        private String dispatchPath = "/servletPath2";

        @Override
        public void init() throws ServletException
        {
            String dispatchTo = getServletConfig().getInitParameter("dispatchPath");
            if (StringUtil.isNotBlank(dispatchTo))
            {
                this.dispatchPath = dispatchTo;
            }
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getParameter("dispatch") != null)
            {
                AsyncContext asyncContext = request.startAsync(request, response);
                asyncContext.dispatch(dispatchPath);
            }
            else
            {
                response.getOutputStream().print("doGet:getServletPath:" + request.getServletPath() + "\n");
                response.getOutputStream().print("doGet:getRequestURI:" + request.getRequestURI() + "\n");
                response.getOutputStream().print("doGet:getRequestURL:" + request.getRequestURL() + "\n");
                response.getOutputStream().print("doGet:getPathInfo:" + request.getPathInfo() + "\n");
                AsyncContext asyncContext = request.startAsync(request, response);
                HttpServletRequest asyncRequest = (HttpServletRequest)asyncContext.getRequest();
                response.getOutputStream().print("doGet:async:getServletPath:" + asyncRequest.getServletPath() + "\n");
                response.getOutputStream().print("doGet:async:getRequestURI:" + asyncRequest.getRequestURI() + "\n");
                response.getOutputStream().print("doGet:async:getRequestURL:" + asyncRequest.getRequestURL() + "\n");
                response.getOutputStream().print("doGet:async:getPathInfo:" + asyncRequest.getPathInfo() + "\n");
                asyncContext.start(new AsyncRunnable(asyncContext));
            }
        }
    }

    private class TestServlet2 extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.getOutputStream().print("doGet:getServletPath:" + request.getServletPath() + "\n");
            response.getOutputStream().print("doGet:getRequestURI:" + request.getRequestURI() + "\n");
            response.getOutputStream().print("doGet:getRequestURL:" + request.getRequestURL() + "\n");
            response.getOutputStream().print("doGet:getPathInfo:" + request.getPathInfo() + "\n");
            AsyncContext asyncContext = request.startAsync(request, response);
            HttpServletRequest asyncRequest = (HttpServletRequest)asyncContext.getRequest();
            response.getOutputStream().print("doGet:async:getServletPath:" + asyncRequest.getServletPath() + "\n");
            response.getOutputStream().print("doGet:async:getRequestURI:" + asyncRequest.getRequestURI() + "\n");
            response.getOutputStream().print("doGet:async:getRequestURL:" + asyncRequest.getRequestURL() + "\n");
            response.getOutputStream().print("doGet:async:getPathInfo:" + asyncRequest.getPathInfo() + "\n");
            asyncContext.start(new AsyncRunnable(asyncContext));
        }
    }

    private class TestStartThrowServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() == DispatcherType.REQUEST)
            {
                request.startAsync(request, response);

                if (Boolean.parseBoolean(request.getParameter("dispatch")))
                {
                    request.getAsyncContext().dispatch();
                }

                if (Boolean.parseBoolean(request.getParameter("complete")))
                {
                    response.getOutputStream().write("completeBeforeThrow".getBytes());
                    if (Boolean.parseBoolean(request.getParameter("flush")))
                        response.flushBuffer();
                    request.getAsyncContext().complete();
                }

                throw new QuietServletException(new IOException("Test"));
            }
        }
    }

    private class AsyncRunnable implements Runnable
    {
        private AsyncContext _context;

        public AsyncRunnable(AsyncContext context)
        {
            _context = context;
        }

        @Override
        public void run()
        {
            HttpServletRequest req = (HttpServletRequest)_context.getRequest();

            try
            {
                _context.getResponse().getOutputStream().print("async:run:attr:servletPath:" + req.getAttribute(AsyncContext.ASYNC_SERVLET_PATH) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:pathInfo:" + req.getAttribute(AsyncContext.ASYNC_PATH_INFO) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:queryString:" + req.getAttribute(AsyncContext.ASYNC_QUERY_STRING) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:contextPath:" + req.getAttribute(AsyncContext.ASYNC_CONTEXT_PATH) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:requestURI:" + req.getAttribute(AsyncContext.ASYNC_REQUEST_URI) + "\n");
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            _context.complete();
        }
    }

    private class Wrapper extends HttpServletResponseWrapper
    {
        public Wrapper(HttpServletResponse response)
        {
            super(response);
        }
    }
}
