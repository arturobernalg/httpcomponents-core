package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.DuplexChannel;
import org.junit.jupiter.api.Test;

/**
 * RFC 6455 §5.7 examples via real engines (masking, fragmentation, ping/pong, len=126/127).
 */
public class WsEngineRfcExamplesTest {

    @Test
    void singleFrameText_clientMasked_serverUnmasked_echo() {
        final Wire w = Wire.create();
        final WsConfig cfg = WsConfig.custom().build();

        final Events clientEv = new Events();
        final Events serverEv = new Events();

        final WsEngine client = new WsEngine(w.left, clientEv, cfg, true);
        final WsEngine server = new WsEngine(w.right, serverEv, cfg, false);
        clientEv.open(client);
        serverEv.open(server);

        client.sendText("Hello", true);
        pump(client, server, 8);
        assertEquals("Hello", serverEv.lastText.get());

        server.sendText(serverEv.lastText.get(), true);
        pump(client, server, 8);
        assertEquals("Hello", clientEv.lastText.get());
    }

    @Test
    void fragmentedUnmaskedText_serverToClient() {
        final Wire w = Wire.create();
        final WsConfig cfg = WsConfig.custom().build();

        final Events clientEv = new Events();
        final WsEngine client = new WsEngine(w.left, clientEv, cfg, true);
        clientEv.open(client);

        final Events serverEv = new Events();
        final WsEngine server = new WsEngine(w.right, serverEv, cfg, false);
        serverEv.open(server);

        // Application-driven fragmentation: TEXT(fin=false) then CONTINUATION(fin=true)
        server.sendText("Hel", false);
        server.sendText("lo", true);

        pump(client, server, 8);

        // Because Events accumulates fragments until last==true,
        // we now see the full "Hello" message.
        assertEquals("Hello", clientEv.lastText.get());
    }

    @Test
    void pingFromServer_clientAutoPongs_maskedPong() {
        final Wire w = Wire.create();
        final WsConfig cfg = WsConfig.custom().build();
        final Events clientEv = new Events();
        final Events serverEv = new Events();

        final WsEngine client = new WsEngine(w.left, clientEv, cfg, true);
        final WsEngine server = new WsEngine(w.right, serverEv, cfg, false);
        clientEv.open(client);
        serverEv.open(server);

        server.sendPing(ByteBuffer.wrap("Hello".getBytes()));
        pump(client, server, 8);

        assertEquals(1, serverEv.pongs.get());
        assertNotNull(serverEv.lastPong);
        assertEquals(5, serverEv.lastPong.remaining());
    }

    @Test
    void binaryLen126_and_127_unmasked_serverToClient() {
        final Wire w = Wire.create();
        final WsConfig cfg = WsConfig.custom().build();
        final Events clientEv = new Events();
        final WsEngine client = new WsEngine(w.left, clientEv, cfg, true);
        clientEv.open(client);
        final Events serverEv = new Events();
        final WsEngine server = new WsEngine(w.right, serverEv, cfg, false);
        serverEv.open(server);

        final byte[] b256 = new byte[256];
        for (int i = 0; i < b256.length; i++) b256[i] = (byte) i;
        server.sendBinary(ByteBuffer.wrap(b256), true);
        pump(client, server, 10);
        assertNotNull(clientEv.lastBinary.get());
        assertEquals(256, clientEv.lastBinary.get().remaining());

        final byte[] b64k = new byte[65536];
        for (int i = 0; i < b64k.length; i++) b64k[i] = (byte) (i & 0xFF);
        server.sendBinary(ByteBuffer.wrap(b64k), true);
        pump(client, server, 20);
        assertNotNull(clientEv.lastBinary.get());
        assertEquals(65536, clientEv.lastBinary.get().remaining());
    }

    /* ---------- helpers ---------- */

    private static void pump(final WsEngine a, final WsEngine b, final int spins) {
        for (int i = 0; i < spins; i++) {
            try {
                a.onWritable();
                b.onReadable();
                b.onWritable();
                a.onReadable();
            } catch (final Exception ignore) {
            }
        }
    }

    /**
     * Minimal in-memory full-duplex pipe.
     */
    static final class Wire {
        final End left = new End();
        final End right = new End();

        static Wire create() {
            final Wire w = new Wire();
            w.left.peer = w.right;
            w.right.peer = w.left;
            return w;
        }

        static final class End implements DuplexChannel {
            End peer;
            final ArrayDeque<ByteBuffer> q = new ArrayDeque<>();
            boolean open = true;

            @Override
            public int read(final ByteBuffer dst) {
                if (!open) return -1;
                if (q.isEmpty()) return 0;
                final ByteBuffer h = q.peekFirst();
                final int n = Math.min(dst.remaining(), h.remaining());
                final int lim = h.limit();
                h.limit(h.position() + n);
                dst.put(h);
                h.limit(lim);
                if (!h.hasRemaining()) q.removeFirst();
                return n;
            }

            @Override
            public int write(final ByteBuffer src) {
                if (!open) return 0;
                if (!src.hasRemaining()) return 0;
                final ByteBuffer c = ByteBuffer.allocate(src.remaining());
                c.put(src).flip();
                peer.q.add(c);
                return c.remaining();
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

    /**
     * Captures events for assertions — accumulates text fragments until last==true.
     */
    static final class Events implements WebSocketListener {
        final StringBuilder textAcc = new StringBuilder();
        final AtomicReference<String> lastText = new AtomicReference<>();
        final AtomicReference<ByteBuffer> lastBinary = new AtomicReference<>();
        final AtomicInteger pings = new AtomicInteger();
        final AtomicInteger pongs = new AtomicInteger();
        volatile ByteBuffer lastPong;

        void open(final WebSocketSession s) {
            onOpen(s);
        }

        @Override
        public void onOpen(final WebSocketSession s) {
        }

        @Override
        public void onText(final WebSocketSession s, final CharSequence data, final boolean last) {
            textAcc.append(data);
            if (last) {
                lastText.set(textAcc.toString());
                textAcc.setLength(0);
            }
        }

        @Override
        public void onBinary(final WebSocketSession s, final ByteBuffer data, final boolean last) {
            lastBinary.set(data.asReadOnlyBuffer());
        }

        @Override
        public void onPing(final WebSocketSession s, final ByteBuffer data) {
            pings.incrementAndGet();
        }

        @Override
        public void onPong(final WebSocketSession s, final ByteBuffer data) {
            lastPong = data.asReadOnlyBuffer();
            pongs.incrementAndGet();
        }

        @Override
        public void onClose(final WebSocketSession s, final int code, final String reason) {
        }

        @Override
        public void onError(final WebSocketSession s, final Exception ex) {
            fail(ex);
        }
    }
}
