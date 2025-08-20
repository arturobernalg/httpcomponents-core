package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.DuplexChannel;
import org.junit.jupiter.api.Test;

public class WsEngineAutoPongOffTest {

    @Test
    void no_auto_pong_until_manual() {
        final Wire w = Wire.create();
        final WsConfig cfg = WsConfig.custom().setAutoPong(false).build();

        final Probe client = new Probe();
        final Probe server = new Probe();
        final WsEngine c = new WsEngine(w.left, client, cfg, true);
        final WsEngine s = new WsEngine(w.right, server, cfg, false);
        client.onOpen(c);
        server.onOpen(s);

        s.sendPing(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        pump(c, s, 6);

        assertEquals(0, server.pongs.get());

        c.sendPong(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        pump(c, s, 6);

        assertEquals(1, server.pongs.get());
    }

    /* helpers */
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
                if (!open || !src.hasRemaining()) return 0;
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

    static final class Probe implements WebSocketListener {
        final AtomicInteger pongs = new AtomicInteger();

        @Override
        public void onOpen(final WebSocketSession s) {
        }

        @Override
        public void onText(final WebSocketSession s, final CharSequence d, final boolean last) {
        }

        @Override
        public void onBinary(final WebSocketSession s, final ByteBuffer d, final boolean last) {
        }

        @Override
        public void onPing(final WebSocketSession s, final ByteBuffer d) {
        }

        @Override
        public void onPong(final WebSocketSession s, final ByteBuffer d) {
            pongs.incrementAndGet();
        }

        @Override
        public void onClose(final WebSocketSession s, final int c, final String r) {
        }

        @Override
        public void onError(final WebSocketSession s, final Exception e) {
            fail(e);
        }
    }
}
