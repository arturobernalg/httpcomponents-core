package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.core5.ws.engine.WsEngine;
import org.apache.hc.core5.ws.io.DuplexChannel;
import org.junit.jupiter.api.Test;

/**
 * Validates RSV bits and control-frame constraints per RFC 6455 §§5.2–5.5.
 */
public class WsProtocolValidationTest {

    @Test
    void rsv2SetShouldFailProtocol() throws IOException {
        final Feed ch = new Feed();
        final Probe probe = new Probe();
        final WsEngine server = new WsEngine(ch, probe, WsConfig.custom().build(), false);
        probe.open(server);

        // Craft a bogus frame: FIN=1, RSV2=1, opcode=TEXT, payload "x"
        byte b0 = (byte) 0x80; // FIN=1
        b0 |= (byte) 0x40;     // RSV2=1
        b0 |= 0x1;            // TEXT
        final byte b1 = 0x01;       // unmasked, len=1
        ch.feed(new byte[]{b0, b1, 'x'});

        server.onReadable(); // should trigger protocol error close
        assertEquals(1, probe.closes.get());
        assertEquals(CloseCode.PROTOCOL_ERROR, probe.lastCloseCode);
    }

    @Test
    void controlFrameMustNotBeFragmentedOrBig() throws IOException {
        final Feed ch = new Feed();
        final Probe probe = new Probe();
        final WsEngine server = new WsEngine(ch, probe, WsConfig.custom().build(), false);
        probe.open(server);

        // Ping with FIN=0 (fragmented control) -> protocol error
        final byte b0 = 0x09; // PING opcode, FIN=0
        final byte b1 = 0x01; // len=1
        ch.feed(new byte[]{b0, b1, 0x00});
        server.onReadable();
        assertEquals(CloseCode.PROTOCOL_ERROR, probe.lastCloseCode);

        // Oversized control: length=126 (encoded)
        final Feed ch2 = new Feed();
        final Probe probe2 = new Probe();
        final WsEngine srv2 = new WsEngine(ch2, probe2, WsConfig.custom().build(), false);
        probe2.open(srv2);
        final byte[] hdr = new byte[]{(byte) 0x89, 126, 0x01, 0x00}; // PING, len=256 (illegal)
        final byte[] body = new byte[256];
        ch2.feed(hdr);
        ch2.feed(body);
        srv2.onReadable();
        assertEquals(CloseCode.PROTOCOL_ERROR, probe2.lastCloseCode);
    }

    /* ---------- helpers ---------- */

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
        } // ignore writes

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

        void open(final WebSocketSession s) {
            onOpen(s);
        }

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
        public void onError(final WebSocketSession s, final Exception ex) {
        }
    }
}