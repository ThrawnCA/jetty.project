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

package org.eclipse.jetty.http2;

import java.io.IOException;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>The SPI interface for implementing a HTTP/2 session.</p>
 * <p>This class extends {@link Session} by adding the methods required to
 * implement the HTTP/2 session functionalities.</p>
 */
public interface ISession extends Session
{
    @Override
    IStream getStream(int streamId);

    /**
     * <p>Removes the given {@code stream}.</p>
     *
     * @param stream the stream to remove
     */
    void removeStream(IStream stream);

    /**
     * <p>Enqueues the given frames to be written to the connection.</p>
     *
     * @param stream the stream the frames belong to
     * @param callback the callback that gets notified when the frames have been sent
     * @param frame the first frame to enqueue
     * @param frames additional frames to enqueue
     */
    void frames(IStream stream, Callback callback, Frame frame, Frame... frames);

    /**
     * <p>Enqueues the given PUSH_PROMISE frame to be written to the connection.</p>
     * <p>Differently from {@link #frames(IStream, Callback, Frame, Frame...)}, this method
     * generates atomically the stream id for the pushed stream.</p>
     *
     * @param stream the stream associated to the pushed stream
     * @param promise the promise that gets notified of the pushed stream creation
     * @param frame the PUSH_PROMISE frame to enqueue
     * @param listener the listener that gets notified of pushed stream events
     */
    void push(IStream stream, Promise<Stream> promise, PushPromiseFrame frame, Stream.Listener listener);

    /**
     * <p>Enqueues the given DATA frame to be written to the connection.</p>
     *
     * @param stream the stream the data frame belongs to
     * @param callback the callback that gets notified when the frame has been sent
     * @param frame the DATA frame to send
     */
    void data(IStream stream, Callback callback, DataFrame frame);

    /**
     * <p>Updates the session send window by the given {@code delta}.</p>
     *
     * @param delta the delta value (positive or negative) to add to the session send window
     * @return the previous value of the session send window
     */
    int updateSendWindow(int delta);

    /**
     * <p>Updates the session receive window by the given {@code delta}.</p>
     *
     * @param delta the delta value (positive or negative) to add to the session receive window
     * @return the previous value of the session receive window
     */
    int updateRecvWindow(int delta);

    /**
     * <p>Callback method invoked when a WINDOW_UPDATE frame has been received.</p>
     *
     * @param stream the stream the window update belongs to, or null if the window update belongs to the session
     * @param frame the WINDOW_UPDATE frame received
     */
    void onWindowUpdate(IStream stream, WindowUpdateFrame frame);

    /**
     * @return whether the push functionality is enabled
     */
    boolean isPushEnabled();

    /**
     * <p>Callback invoked when the connection reads -1.</p>
     *
     * @see #onIdleTimeout()
     * @see #close(int, String, Callback)
     */
    void onShutdown();

    /**
     * <p>Callback invoked when the idle timeout expires.</p>
     *
     * @return {@code true} if the session has expired
     * @see #onShutdown()
     * @see #close(int, String, Callback)
     */
    boolean onIdleTimeout();

    /**
     * <p>Callback method invoked during an HTTP/1.1 to HTTP/2 upgrade requests
     * to process the given synthetic frame.</p>
     *
     * @param frame the synthetic frame to process
     */
    void onFrame(Frame frame);

    /**
     * <p>Callback method invoked when bytes are flushed to the network.</p>
     *
     * @param bytes the number of bytes flushed to the network
     * @throws IOException if the flush should fail
     */
    void onFlushed(long bytes) throws IOException;

    /**
     * @return the number of bytes written by this session
     */
    long getBytesWritten();

    /**
     * <p>Callback method invoked when a DATA frame is received.</p>
     *
     * @param frame the DATA frame received
     * @param callback the callback to notify when the frame has been processed
     */
    void onData(DataFrame frame, Callback callback);
}
