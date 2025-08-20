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
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsConfig;
import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.DuplexChannel;

/**
 * "Classic" blocking-style demo built on top of WsEngine.
 * A small pump thread drives the engine so calls like sendText()/close() feel synchronous.
 * <p>
 * Only this class sits in the examples package; core/client classes remain in their packages.
 */
public final class ClassicWsLoopbackDemo {

    public static void main(final String[] args) throws Exception {
        final InMemoryDuplexPair wire = InMemoryDuplexPair.create();

        final WsConfig cfg = WsConfig.custom()
                .enablePerMessageDeflate(true)
                .setClientNoContextTakeover(true)
                .setServerNoContextTakeover(true)
                .build();

        // ----- Server side (echo) -----
        final WebSocketListener serverL = new WebSocketListener() {
            @Override
            public void onOpen(final WebSocketSession s) {
                System.out.println("[server] open");
            }

            @Override
            public void onText(final WebSocketSession s, final CharSequence data, final boolean last) {
                System.out.println("[server] recv: " + preview(data));
                s.sendText(data, last);
            }

            @Override
            public void onPing(final WebSocketSession s, final ByteBuffer data) {
                System.out.println("[server] ping(" + data.remaining() + "B)"); // engine auto-pongs
            }

            @Override
            public void onClose(final WebSocketSession s, final int code, final String reason) {
                System.out.println("[server] close " + code + " " + reason);
            }
        };
        final WsEngine serverEngine = new WsEngine(wire.right, serverL, cfg, false);
        final EnginePump serverPump = new EnginePump(serverEngine, "ws-server-pump");
        serverPump.start();
        serverL.onOpen(serverEngine);

        // ----- Client side (classic facade) -----
        final BlockingWebSocketClient client = new BlockingWebSocketClient(wire.left, cfg);
        client.connect(new WebSocketListener() {
            @Override
            public void onOpen(final WebSocketSession s) {
                System.out.println("[client] open");
            }

            @Override
            public void onText(final WebSocketSession s, final CharSequence data, final boolean last) {
                System.out.println("[client] recv: " + preview(data));
            }

            @Override
            public void onPong(final WebSocketSession s, final ByteBuffer data) {
                System.out.println("[client] pong(" + data.remaining() + "B)");
            }

            @Override
            public void onClose(final WebSocketSession s, final int code, final String reason) {
                System.out.println("[client] close " + code + " " + reason);
            }
        });

        // Blocking-style usage:
        client.sendText("hola", true);                // blocks until queued (facade semantics)
        client.ping(new byte[]{1, 2, 3});              // blocks until queued
        client.awaitText(5, TimeUnit.SECONDS);       // wait until a text arrives
        client.close(1000, "done");                  // initiate close
        client.awaitClose(5, TimeUnit.SECONDS);      // wait for close event

        // Shutdown pumps
        client.shutdown();
        serverPump.stopPump();
    }

    /* ---------------- Blocking facade ---------------- */

    /**
     * Minimal blocking-style client facade around a single WsEngine and a pump thread.
     */
    private static final class BlockingWebSocketClient {
        private final WsEngine engine;
        private final EnginePump pump;
        private final CompositeListener listeners;        // fan-out to internal + app listener
        private final CountDownLatch textLatch = new CountDownLatch(1);
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        BlockingWebSocketClient(final DuplexChannel channel, final WsConfig cfg) {
            this.listeners = new CompositeListener();

            // Internal listener to trip latches for the "blocking" waits
            this.listeners.add(new WebSocketListener() {
                @Override
                public void onText(final WebSocketSession s, final CharSequence d, final boolean last) {
                    textLatch.countDown();
                }

                @Override
                public void onClose(final WebSocketSession s, final int code, final String reason) {
                    closeLatch.countDown();
                }
            });

            this.engine = new WsEngine(channel, listeners, cfg, true);
            this.pump = new EnginePump(engine, "ws-client-pump");
        }

        /**
         * Connect synchronously: register the app listener, start pump, and fire onOpen.
         */
        void connect(final WebSocketListener appListener) {
            listeners.add(appListener);
            pump.start();
            appListener.onOpen(engine);  // since we're not in a reactor, we trigger it here
        }

        void sendText(final String s, final boolean last) {
            engine.sendText(s, last).join();
        }

        void ping(final byte[] payload) {
            engine.sendPing(ByteBuffer.wrap(payload)).join();
        }

        void close(final int code, final String reason) {
            engine.close(code, reason).join();
        }

        boolean awaitText(final long t, final TimeUnit u) throws InterruptedException {
            return textLatch.await(t, u);
        }

        boolean awaitClose(final long t, final TimeUnit u) throws InterruptedException {
            return closeLatch.await(t, u);
        }

        void shutdown() {
            pump.stopPump();
        }

        /**
         * Simple listener aggregator
         */
        private static final class CompositeListener implements WebSocketListener {
            private final java.util.List<WebSocketListener> list =
                    new java.util.concurrent.CopyOnWriteArrayList<>();

            void add(final WebSocketListener l) {
                list.add(l);
            }

            @Override
            public void onOpen(final WebSocketSession s) {
                for (WebSocketListener l : list) l.onOpen(s);
            }

            @Override
            public void onText(final WebSocketSession s, final CharSequence d, final boolean last) {
                for (WebSocketListener l : list) l.onText(s, d, last);
            }

            @Override
            public void onBinary(final WebSocketSession s, final ByteBuffer b, final boolean last) {
                for (WebSocketListener l : list) l.onBinary(s, b, last);
            }

            @Override
            public void onPing(final WebSocketSession s, final ByteBuffer b) {
                for (WebSocketListener l : list) l.onPing(s, b);
            }

            @Override
            public void onPong(final WebSocketSession s, final ByteBuffer b) {
                for (WebSocketListener l : list) l.onPong(s, b);
            }

            @Override
            public void onClose(final WebSocketSession s, final int c, final String r) {
                for (WebSocketListener l : list) l.onClose(s, c, r);
            }

            @Override
            public void onError(final WebSocketSession s, final Exception e) {
                for (WebSocketListener l : list) l.onError(s, e);
            }
        }
    }

    /**
     * Simple pump that drives a WsEngine on its channel using a background thread.
     */
    private static final class EnginePump implements Runnable {
        private final WsEngine engine;
        private final Thread thread;
        private volatile boolean running = true;
        final WebSocketListener listener = new WebSocketListener() {
        }; // placeholder for composite in client

        EnginePump(final WsEngine engine, final String name) {
            this.engine = engine;
            this.thread = new Thread(this, name);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stopPump() {
            running = false;
            try {
                thread.join(200);
            } catch (InterruptedException ignore) {
            }
        }

        @Override
        public void run() {
            try {
                while (running || engine.isOpen()) {
                    engine.onWritable();
                    engine.onReadable();
                    Thread.yield();
                }
            } catch (final Exception ignore) {
                // swallow in demo
            }
        }
    }

    /* ---------------- in-memory wire (same as other example, without counters) ---------------- */

    private static final class InMemoryDuplexPair {
        final DuplexChannel left;
        final DuplexChannel right;

        private InMemoryDuplexPair(final Endpoint a, final Endpoint b) {
            this.left = a;
            this.right = b;
        }

        static InMemoryDuplexPair create() {
            final Endpoint a = new Endpoint();
            final Endpoint b = new Endpoint();
            a.peer = b;
            b.peer = a;
            return new InMemoryDuplexPair(a, b);
        }

        private static final class Endpoint implements DuplexChannel {
            private final ArrayDeque<ByteBuffer> inbox = new ArrayDeque<ByteBuffer>();
            private Endpoint peer;
            private boolean open = true;

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
                return copy.remaining();
            }

            @Override
            public void flush() {
            }

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

    private static String preview(final CharSequence cs) {
        final int len = cs.length();
        final int cut = Math.min(len, 20);
        return '"' + cs.subSequence(0, cut).toString() + (len > cut ? "...(" + len + " chars)" : "\"");
    }
}
