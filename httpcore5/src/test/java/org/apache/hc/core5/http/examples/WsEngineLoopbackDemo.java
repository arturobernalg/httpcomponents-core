/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information regarding
 * copyright ownership.  The ASF licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 * ====================================================================
 */
package org.apache.hc.core5.http.examples;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import org.apache.hc.core5.ws.CloseCode;
import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;
import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.DuplexChannel;

/**
 * Demo: WsEngine with RFC 7692 permessage-deflate enabled.
 * Uses an in-memory wire to exercise TEXT echo (large & compressible),
 * auto PING/PONG, and a clean CLOSE handshake on both sides.
 * <p>
 * Only this class lives in the examples package; core/client classes remain in their packages.
 */
public final class WsEngineLoopbackDemo {

    public static void main(final String[] args) throws Exception {
        final InMemoryDuplexPair wire = InMemoryDuplexPair.create();

        // Enable compression; sane defaults mimic common browser behavior.
        final WsConfig cfg = WsConfig.custom()
                .enablePerMessageDeflate(true)
                .setClientNoContextTakeover(true)
                .setServerNoContextTakeover(true)
                .build();

        final WebSocketListener clientL = new WebSocketListener() {
            @Override
            public void onOpen(final WebSocketSession s) {
                System.out.println("[client] open");
            }

            @Override
            public void onText(final WebSocketSession s, final CharSequence data, final boolean last) {
                System.out.println("[client] recv text: " + preview(data) + " last=" + last);
                s.close(CloseCode.NORMAL_CLOSURE, "done");
            }

            @Override
            public void onPong(final WebSocketSession s, final ByteBuffer data) {
                System.out.println("[client] pong(" + data.remaining() + "B)");
            }

            @Override
            public void onClose(final WebSocketSession s, final int code, final String reason) {
                System.out.println("[client] close " + code + " " + reason);
            }

            @Override
            public void onError(final WebSocketSession s, final Exception ex) {
                ex.printStackTrace(System.out);
            }
        };

        final WebSocketListener serverL = new WebSocketListener() {
            @Override
            public void onOpen(final WebSocketSession s) {
                System.out.println("[server] open");
            }

            @Override
            public void onText(final WebSocketSession s, final CharSequence data, final boolean last) {
                System.out.println("[server] recv text: " + preview(data) + " last=" + last);
                s.sendText(data, last); // echo
            }

            @Override
            public void onPing(final WebSocketSession s, final ByteBuffer data) {
                System.out.println("[server] ping(" + data.remaining() + "B)");
                // No manual sendPong: engine auto-pongs.
            }

            @Override
            public void onClose(final WebSocketSession s, final int code, final String reason) {
                System.out.println("[server] close " + code + " " + reason);
            }

            @Override
            public void onError(final WebSocketSession s, final Exception ex) {
                ex.printStackTrace(System.out);
            }
        };

        final WsEngine client = new WsEngine(wire.left, clientL, cfg, true);
        final WsEngine server = new WsEngine(wire.right, serverL, cfg, false);

        clientL.onOpen(client);
        serverL.onOpen(server);

        // Send a LARGE, highly-compressible message to make deflate effects visible on the wire counters.
        final String payload = repeat("hola ", 5000); // 25,000 chars; very compressible
        final int rawBytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(payload)).remaining();
        System.out.println("[info] raw text payload bytes (UTF-8): " + rawBytes);

        client.sendText(payload, true);
        client.sendPing(ByteBuffer.wrap(new byte[]{1, 2, 3}));

        // Pump a few rounds (a reactor would call these on readiness).
        for (int i = 0; i < 20; i++) {
            client.onWritable();
            server.onReadable();
            server.onWritable();
            client.onReadable();
        }

        // Drain so both sides observe the echoed CLOSE.
        int guard = 0;
        while ((client.isOpen() || server.isOpen()) && guard++ < 100) {
            client.onWritable();
            server.onReadable();
            server.onWritable();
            client.onReadable();
        }

        // Print on-wire byte counts to show compression benefit (includes frame headers).
        System.out.println("[wire] client -> server bytes (on-wire): " + wire.getBytesLeftToRight());
        System.out.println("[wire] server -> client bytes (on-wire): " + wire.getBytesRightToLeft());

        client.close();
        server.close();
    }

    private static String repeat(final String s, final int n) {
        final StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static String preview(final CharSequence cs) {
        final int len = cs.length();
        final int cut = Math.min(len, 20);
        return '"' + cs.subSequence(0, cut).toString() + (len > cut ? "...(" + len + " chars)" : "\"");
    }

    /**
     * In-memory connected wire used only by this example (counts bytes to show compression).
     */
    private static final class InMemoryDuplexPair {
        final Endpoint a;  // left
        final Endpoint b;  // right
        final DuplexChannel left;
        final DuplexChannel right;

        private InMemoryDuplexPair(final Endpoint a, final Endpoint b) {
            this.a = a;
            this.b = b;
            this.left = a;
            this.right = b;
        }

        static InMemoryDuplexPair create() {
            final InMemoryDuplexPair pair = new InMemoryDuplexPair(new Endpoint(), new Endpoint());
            pair.a.peer = pair.b;
            pair.b.peer = pair.a;
            return pair;
        }

        long getBytesLeftToRight() {
            return a.bytesOut;
        }

        long getBytesRightToLeft() {
            return b.bytesOut;
        }

        private static final class Endpoint implements DuplexChannel {
            private final ArrayDeque<ByteBuffer> inbox = new ArrayDeque<ByteBuffer>();
            private Endpoint peer;
            private boolean open = true;
            private long bytesOut = 0L;

            @Override
            public int read(final ByteBuffer dst) {
                if (!open) return -1;
                if (inbox.isEmpty()) return 0;
                final ByteBuffer head = inbox.peekFirst();
                final int n = Math.min(dst.remaining(), head.remaining());
                if (n == 0) return 0;
                final int oldLimit = head.limit();
                head.limit(head.position() + n);
                dst.put(head);
                head.limit(oldLimit);
                if (!head.hasRemaining()) inbox.removeFirst();
                return n;
            }

            @Override
            public int write(final ByteBuffer src) {
                if (!open || !src.hasRemaining()) return 0;
                final ByteBuffer copy = ByteBuffer.allocate(src.remaining());
                copy.put(src).flip();
                peer.inbox.add(copy);
                bytesOut += copy.remaining(); // count bytes we put "on the wire"
                return copy.remaining();
            }

            @Override
            public void flush() { /* no-op */ }

            @Override
            public void close() {
                open = false;
            }

            @Override
            public boolean isOpen() {
                return open;
            }
        }
    }
}
