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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mockito;

class H2StreamProtocolIOSessionTest {

    @Captor
    private ArgumentCaptor<ByteBuffer> byteBufferCaptor;

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

    @Test
    void writeAndFlushShouldSendBytesThroughStreamChannel() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);

        final AtomicReference<byte[]> captured = new AtomicReference<>();

        when(channel.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
            final ByteBuffer src = invocation.getArgument(0, ByteBuffer.class);
            final int remaining = src.remaining();
            final byte[] data = new byte[remaining];
            src.get(data);
            captured.set(data);
            return remaining;
        });

        final H2StreamProtocolIOSession session = new H2StreamProtocolIOSession("test-id", endpoint, channel);

        final byte[] payload = "hello".getBytes(StandardCharsets.US_ASCII);
        final ByteBuffer buffer = ByteBuffer.wrap(payload);

        final int written = session.write(buffer);
        assertEquals(payload.length, written);

        final boolean flushed = session.flushToStream();
        assertTrue(flushed);

        verify(channel, times(1)).write(any(ByteBuffer.class));
        assertArrayEquals(payload, captured.get());
    }

    @Test
    void closeShouldChangeStatusAndNotifyHandler() {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session = new H2StreamProtocolIOSession("session-1", endpoint, channel);

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

        session.upgrade(handler);

        assertTrue(session.isOpen());
        assertEquals(IOSession.Status.ACTIVE, session.getStatus());

        session.close(CloseMode.GRACEFUL);

        assertFalse(session.isOpen());
        assertEquals(IOSession.Status.CLOSED, session.getStatus());
        assertTrue(disconnected.get());
    }

    @Test
    void enqueueAndPollShouldWorkForCommands() {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session = new H2StreamProtocolIOSession("session-3", endpoint, channel);

        assertFalse(session.hasCommands());

        final DummyCommand command = new DummyCommand();
        session.enqueue(command, org.apache.hc.core5.reactor.Command.Priority.NORMAL);

        assertTrue(session.hasCommands());
        assertSame(command, session.poll());
        assertFalse(session.hasCommands());
    }

    private static final class DummyCommand implements org.apache.hc.core5.reactor.Command {
        @Override
        public boolean cancel() {
            return true;
        }
    }

    @Test
    void onConnectedShouldNotifyHandler() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = Mockito.mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session =
                new H2StreamProtocolIOSession("tunnel-1", endpoint, channel);

        final IOEventHandler handler = Mockito.mock(IOEventHandler.class);
        session.upgrade(handler);

        session.onConnected();

        Mockito.verify(handler).connected(ArgumentMatchers.same(session));
    }

    @Test
    void onInputShouldDeliverBufferToHandlerWhenNonEmpty() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = Mockito.mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session =
                new H2StreamProtocolIOSession("tunnel-2", endpoint, channel);

        final IOEventHandler handler = Mockito.mock(IOEventHandler.class);
        session.upgrade(handler);

        final ByteBuffer src = ByteBuffer.wrap("test".getBytes(StandardCharsets.US_ASCII));
        session.onInput(src);

        Mockito.verify(handler, Mockito.times(1))
                .inputReady(
                        ArgumentMatchers.same((IOSession) session),
                        ArgumentMatchers.any(ByteBuffer.class));
    }

    @Test
    void onInputShouldIgnoreNullOrEmptyBuffers() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = Mockito.mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session =
                new H2StreamProtocolIOSession("tunnel-3", endpoint, channel);

        final IOEventHandler handler = Mockito.mock(IOEventHandler.class);
        session.upgrade(handler);

        session.onInput(null);
        session.onInput(ByteBuffer.allocate(0));

        Mockito.verify(handler, Mockito.never())
                .inputReady(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void onRemoteEndStreamShouldNotifyTimeout() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = Mockito.mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session =
                new H2StreamProtocolIOSession("tunnel-4", endpoint, channel);

        final IOEventHandler handler = Mockito.mock(IOEventHandler.class);
        session.upgrade(handler);

        session.onRemoteEndStream();

        Mockito.verify(handler).timeout(
                ArgumentMatchers.same(session),
                ArgumentMatchers.eq(Timeout.ZERO_MILLISECONDS));
    }

    @Test
    void onRemoteEndStreamWithoutHandlerShouldNotThrow() throws Exception {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = Mockito.mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session =
                new H2StreamProtocolIOSession("tunnel-5", endpoint, channel);

        session.onRemoteEndStream();
    }

    @Test
    void onDisconnectedShouldCloseSessionAndNotifyHandler() {
        final NamedEndpoint endpoint = endpoint("example.com", 443);
        final H2StreamChannel channel = Mockito.mock(H2StreamChannel.class);
        final H2StreamProtocolIOSession session =
                new H2StreamProtocolIOSession("tunnel-6", endpoint, channel);

        final IOEventHandler handler = Mockito.mock(IOEventHandler.class);
        session.upgrade(handler);

        session.onDisconnected();

        Assertions.assertFalse(session.isOpen());
        Assertions.assertEquals(IOSession.Status.CLOSED, session.getStatus());
        Mockito.verify(handler).disconnected(ArgumentMatchers.same(session));
    }


}
