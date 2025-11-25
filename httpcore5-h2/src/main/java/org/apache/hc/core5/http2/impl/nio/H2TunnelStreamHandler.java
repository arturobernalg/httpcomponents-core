/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http2.impl.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http2.H2Error;
import org.apache.hc.core5.http2.H2PseudoRequestHeaders;
import org.apache.hc.core5.http2.H2StreamResetException;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Args;

@Internal
@Contract(threading = ThreadingBehavior.SAFE)
final class H2TunnelStreamHandler implements H2StreamHandler {

    private enum State {
        INIT,
        ACTIVE,
        FAILED,
        CLOSED
    }

    private final H2StreamChannel channel;
    private final HandlerFactory<AsyncPushConsumer> pushHandlerFactory;
    private final H2StreamProtocolIOSession tunnelSession;
    private final FutureCallback<ProtocolIOSession> callback;

    private volatile State state;
    private volatile Exception failure;
    private volatile boolean localEndStream;
    private volatile boolean callbackCompleted;

    H2TunnelStreamHandler(
            final H2StreamChannel channel,
            final HandlerFactory<AsyncPushConsumer> pushHandlerFactory,
            final H2StreamProtocolIOSession tunnelSession,
            final FutureCallback<ProtocolIOSession> callback,
            final NamedEndpoint targetEndpoint) throws HttpException, IOException {
        this.channel = Args.notNull(channel, "H2 stream channel");
        this.pushHandlerFactory = pushHandlerFactory;
        this.tunnelSession = Args.notNull(tunnelSession, "Tunnel session");
        this.callback = callback;
        this.state = State.INIT;
        this.failure = null;
        this.localEndStream = false;
        this.callbackCompleted = false;

        final List<Header> headers = buildConnectHeaders(targetEndpoint);
        channel.submit(headers, false);
    }

    private static List<Header> buildConnectHeaders(final NamedEndpoint targetEndpoint) {
        final String authority = targetEndpoint.getHostName() + ":" + targetEndpoint.getPort();

        final List<Header> headers = new ArrayList<>(3);
        headers.add(new BasicHeader(H2PseudoRequestHeaders.METHOD, "CONNECT"));
        headers.add(new BasicHeader(H2PseudoRequestHeaders.AUTHORITY, authority));
        // A regular Host header is allowed and typical for proxies
        headers.add(new BasicHeader("host", authority));
        return headers;
    }

    @Override
    public boolean isOutputReady() {
        if (state != State.ACTIVE || localEndStream) {
            return false;
        }
        return tunnelSession.hasBufferedOutput();
    }

    @Override
    public void produceOutput() throws HttpException, IOException {
        if (state != State.ACTIVE || localEndStream) {
            return;
        }
        final boolean wrote = tunnelSession.flushToStream();
        if (!wrote && !tunnelSession.isOpen() && !localEndStream) {
            channel.endStream();
            localEndStream = true;
        }
    }

    @Override
    public void consumePromise(final List<Header> headers) throws HttpException {
        throw new ProtocolException("PUSH_PROMISE is not expected on a tunnel stream");
    }

    @Override
    public void consumeHeader(final List<Header> headers, final boolean endStream)
            throws HttpException, IOException {

        if (state == State.INIT) {
            final int statusCode = parseStatus(headers);
            if (statusCode >= 200 && statusCode < 300) {
                state = State.ACTIVE;
                tunnelSession.onConnected();
                if (callback != null && !callbackCompleted) {
                    callbackCompleted = true;
                    callback.completed(tunnelSession);
                }
            } else {
                state = State.FAILED;
                failure = new ProtocolException("CONNECT failed with status " + statusCode);
                if (callback != null && !callbackCompleted) {
                    callbackCompleted = true;
                    callback.failed(failure);
                }
            }
        }

        if (endStream) {
            tunnelSession.onRemoteEndStream();
            if (state == State.ACTIVE) {
                state = State.CLOSED;
            }
        }
    }

    @Override
    public void updateInputCapacity() throws IOException {
        // keep window large; upper layers can apply back-pressure if needed
        channel.update(65535);
    }

    @Override
    public void consumeData(final ByteBuffer src, final boolean endStream)
            throws HttpException, IOException {

        if (state != State.ACTIVE) {
            failure = new ProtocolException("Unexpected DATA on tunnel stream in state " + state);
            throw new HttpException(failure.getMessage(), failure);
        }

        if (src != null && src.hasRemaining()) {
            final ByteBuffer copy = ByteBuffer.allocate(src.remaining());
            copy.put(src);
            copy.flip();
            tunnelSession.updateReadTime();
            tunnelSession.onInput(copy);
        } else if (src != null) {
            src.position(src.limit());
        }

        if (endStream) {
            tunnelSession.onRemoteEndStream();
            state = State.CLOSED;
        }
    }

    @Override
    public HandlerFactory<AsyncPushConsumer> getPushHandlerFactory() {
        return pushHandlerFactory;
    }

    @Override
    public void failed(final Exception cause) {
        failure = cause;
        if (callback != null && !callbackCompleted) {
            callbackCompleted = true;
            callback.failed(cause);
        }
        tunnelSession.onDisconnected();
    }

    @Override
    public void handle(final HttpException ex, final boolean endStream)
            throws HttpException, IOException {
        failed(ex);
    }

    @Override
    public void releaseResources() {
        // nothing special
    }

    void abort() throws IOException {
        try {
            channel.localReset(H2Error.CANCEL);
        } catch (final H2StreamResetException ignore) {
        } finally {
            localEndStream = true;
            state = State.CLOSED;
            tunnelSession.onDisconnected();
        }
    }

    Exception getFailure() {
        return failure;
    }

    private static int parseStatus(final List<? extends Header> headers) throws ProtocolException {
        if (headers != null) {
            for (final Header header : headers) {
                if (header != null && ":status".equals(header.getName())) {
                    final String value = header.getValue();
                    if (value == null) {
                        throw new ProtocolException("Response :status header is empty");
                    }
                    try {
                        return Integer.parseInt(value);
                    } catch (final NumberFormatException ex) {
                        throw new ProtocolException("Invalid :status code: " + value);
                    }
                }
            }
        }
        throw new ProtocolException("Response :status header is missing");
    }

}
