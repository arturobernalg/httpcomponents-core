package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.DuplexChannel;
import org.junit.jupiter.api.Test;

public class WsEngineRsvValidationTest {

    @Test
    void rsv1_on_data_without_pmd_is_protocol_error_1002() throws Exception {
        final Feed ch = new Feed();
        final Probe p = new Probe();
        final WsEngine server = new WsEngine(ch, p, WsConfig.custom().enablePerMessageDeflate(false).build(), false);
        p.onOpen(server);

        // Craft a TEXT frame: FIN=1, RSV1=1, opcode=1, len=1, payload 'x'
        byte b0 = (byte) 0x80; // FIN
        b0 |= 0x40;            // RSV1
        b0 |= 0x1;             // TEXT
        final byte b1 = 0x01;        // unmasked, len=1
        ch.feed(new byte[]{b0, b1, 'x'});

        server.onReadable();

        assertEquals(1, p.closes.get());
        assertEquals(CloseCode.PROTOCOL_ERROR, p.lastCloseCode);
    }

    /* helpers */
    static final class Feed implements DuplexChannel {
        final ArrayDeque<ByteBuffer> in = new ArrayDeque<>();
        boolean open = true;

        void feed(final byte[] b) {
            in.add(ByteBuffer.wrap(b));
        }

        @Override
        public int read(final ByteBuffer dst) {
            if (!open) return -1;
            if (in.isEmpty()) return 0;
            final ByteBuffer h = in.peekFirst();
            final int n = Math.min(dst.remaining(), h.remaining());
            final int lim = h.limit();
            h.limit(h.position() + n);
            dst.put(h);
            h.limit(lim);
            if (!h.hasRemaining()) in.removeFirst();
            return n;
        }

        @Override
        public int write(final ByteBuffer src) {
            return src.remaining();
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

    static final class Probe implements WebSocketListener {
        final AtomicInteger closes = new AtomicInteger();
        volatile int lastCloseCode;

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
        }

        @Override
        public void onClose(final WebSocketSession s, final int code, final String reason) {
            closes.incrementAndGet();
            lastCloseCode = code;
        }

        @Override
        public void onError(final WebSocketSession s, final Exception e) {
        }
    }
}
