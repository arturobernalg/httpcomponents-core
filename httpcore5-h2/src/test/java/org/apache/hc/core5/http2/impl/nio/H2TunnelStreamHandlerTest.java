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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http2.H2Error;
import org.apache.hc.core5.http2.H2PseudoRequestHeaders;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class H2TunnelStreamHandlerTest {

    private static NamedEndpoint endpoint(final String host, final int port) {
        return new NamedEndpoint() {
            @Override
            public String getHostName() {
                return host;
            }

            @Override
            public int getPort() {
                return port;
            }
        };
    }

    @SuppressWarnings("unchecked")
    @Test
    void constructorShouldSendConnectHeaders() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession tunnelSession = new H2StreamProtocolIOSession("tunnel-1", endpoint, channel);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);
        final HandlerFactory<AsyncPushConsumer> pushHandlerFactory = null;

        new H2TunnelStreamHandler(channel, pushHandlerFactory, tunnelSession, callback, endpoint);

        final ArgumentCaptor<List> headersCaptor =
                (ArgumentCaptor<List>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);

        verify(channel).submit(headersCaptor.capture(), eq(false));

        final List<Header> headers = (List<Header>) headersCaptor.getValue();

        assertFalse(headers.isEmpty());

        boolean methodFound = false;
        boolean authorityFound = false;
        boolean schemeFound = false;
        boolean pathFound = false;

        for (final Header h : headers) {
            if (H2PseudoRequestHeaders.METHOD.equals(h.getName())) {
                methodFound = true;
                assertEquals("CONNECT", h.getValue());
            } else if (H2PseudoRequestHeaders.AUTHORITY.equals(h.getName())) {
                authorityFound = true;
                assertEquals("example.com:443", h.getValue());
            } else if (H2PseudoRequestHeaders.SCHEME.equals(h.getName())) {
                schemeFound = true;
            } else if (H2PseudoRequestHeaders.PATH.equals(h.getName())) {
                pathFound = true;
            }
        }

        assertTrue(methodFound);
        assertTrue(authorityFound);
        assertFalse(schemeFound);
        assertFalse(pathFound);
    }

    @Test
    void consumeHeaderWith2xxStatusShouldCompleteCallback() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession tunnelSession = new H2StreamProtocolIOSession("tunnel-2", endpoint, channel);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);

        final H2TunnelStreamHandler handler = new H2TunnelStreamHandler(
                channel, null, tunnelSession, callback, endpoint);

        final List<Header> headers = new ArrayList<Header>();
        headers.add(new BasicHeader(":status", "200", false));

        handler.consumeHeader(headers, false);

        verify(callback, times(1)).completed(tunnelSession);
        assertNull(handler.getFailure());
    }

    @Test
    void consumeHeaderWithNon2xxStatusShouldFailCallback() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession tunnelSession = new H2StreamProtocolIOSession("tunnel-3", endpoint, channel);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);

        final H2TunnelStreamHandler handler = new H2TunnelStreamHandler(
                channel, null, tunnelSession, callback, endpoint);

        final List<Header> headers = new ArrayList<Header>();
        headers.add(new BasicHeader(":status", "421", false));

        handler.consumeHeader(headers, true);

        verify(callback, times(1)).failed(any(ProtocolException.class));
        assertTrue(handler.getFailure() instanceof ProtocolException);
    }

    @Test
    void consumeDataInActiveStateShouldForwardToTunnelSessionHandler() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession tunnelSession = new H2StreamProtocolIOSession("tunnel-4", endpoint, channel);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);

        final AtomicBoolean connected = new AtomicBoolean(false);
        final AtomicReference<byte[]> received = new AtomicReference<byte[]>();

        final IOEventHandler handler = new IOEventHandler() {
            @Override
            public void connected(final IOSession ioSession) {
                connected.set(true);
            }

            @Override
            public void inputReady(final IOSession ioSession, final ByteBuffer src) {
                final byte[] data = new byte[src.remaining()];
                src.get(data);
                received.set(data);
            }

            @Override
            public void outputReady(final IOSession ioSession) {
            }

            @Override
            public void timeout(final IOSession ioSession, final Timeout timeout) {
            }

            @Override
            public void exception(final IOSession ioSession, final Exception ex) {
            }

            @Override
            public void disconnected(final IOSession ioSession) {
            }
        };

        tunnelSession.upgrade(handler);

        final H2TunnelStreamHandler tunnelHandler = new H2TunnelStreamHandler(
                channel, null, tunnelSession, callback, endpoint);

        final List<Header> headers = new ArrayList<Header>();
        headers.add(new BasicHeader(":status", "200", false));
        tunnelHandler.consumeHeader(headers, false);

        assertTrue(connected.get());

        final ByteBuffer payload = StandardCharsets.US_ASCII.encode("hello");
        tunnelHandler.consumeData(payload, false);

        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), received.get());
    }

    @Test
    void abortShouldResetStreamAndDisconnectTunnelSession() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);

        // ← was anyInt(), now match the H2Error overload
        when(channel.localReset(any(H2Error.class))).thenReturn(true);

        final H2StreamProtocolIOSession tunnelSession = new H2StreamProtocolIOSession("tunnel-5", endpoint, channel);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);

        final AtomicBoolean disconnected = new AtomicBoolean(false);

        final IOEventHandler handler = new IOEventHandler() {
            @Override
            public void connected(final IOSession ioSession) {
            }

            @Override
            public void inputReady(final IOSession ioSession, final ByteBuffer src) {
            }

            @Override
            public void outputReady(final IOSession ioSession) {
            }

            @Override
            public void timeout(final IOSession ioSession, final Timeout timeout) {
            }

            @Override
            public void exception(final IOSession ioSession, final Exception ex) {
            }

            @Override
            public void disconnected(final IOSession ioSession) {
                disconnected.set(true);
            }
        };

        tunnelSession.upgrade(handler);

        final H2TunnelStreamHandler tunnelHandler = new H2TunnelStreamHandler(
                channel, null, tunnelSession, callback, endpoint);

        tunnelHandler.abort();

        // ← expect the enum, not the numeric code
        verify(channel, times(1)).localReset(H2Error.CANCEL);
        assertTrue(disconnected.get());
    }


    @Test
    void failedShouldRecordFailureAndNotifyCallback() throws HttpException, IOException {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession tunnelSession = new H2StreamProtocolIOSession("tunnel-6", endpoint, channel);
        final FutureCallback<ProtocolIOSession> callback = mock(FutureCallback.class);

        final H2TunnelStreamHandler tunnelHandler = new H2TunnelStreamHandler(
                channel, null, tunnelSession, callback, endpoint);

        final IOException cause = new IOException("boom");
        tunnelHandler.failed(cause);

        assertSame(cause, tunnelHandler.getFailure());
        verify(callback, times(1)).failed(cause);
    }

}
