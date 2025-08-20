package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

public class WsMessageAggregatorUtf8Test {

    @Test
    void euro_sign_split_across_fragments_decodes_ok() throws Exception {
        final WsMessageAggregator aggr = new WsMessageAggregator(true, 1 << 20);
        final Capture cap = new Capture();
        final WebSocketSession sess = new NullSession();

        // '€' U+20AC => E2 82 AC (3 bytes). Send first 2 bytes (not FIN), then last byte (FIN).
        aggr.onFrame(new WsFrame(false, false, false, false, WsOpcode.TEXT,
                        ByteBuffer.wrap(new byte[]{(byte) 0xE2, (byte) 0x82})),
                cap, sess);
        aggr.onFrame(new WsFrame(true, false, false, false, WsOpcode.CONTINUATION,
                        ByteBuffer.wrap(new byte[]{(byte) 0xAC})),
                cap, sess);

        assertEquals("€", cap.lastText.get());
    }

    /* helpers */
    static final class Capture implements WebSocketListener {
        final AtomicReference<String> lastText = new AtomicReference<>();

        @Override
        public void onOpen(final WebSocketSession s) {
        }

        @Override
        public void onText(final WebSocketSession s, final CharSequence data, final boolean last) {
            if (last) lastText.set(data.toString());
        }

        @Override
        public void onBinary(final WebSocketSession s, final ByteBuffer data, final boolean last) {
        }

        @Override
        public void onPing(final WebSocketSession s, final ByteBuffer data) {
        }

        @Override
        public void onPong(final WebSocketSession s, final ByteBuffer data) {
        }

        @Override
        public void onClose(final WebSocketSession s, final int code, final String reason) {
        }

        @Override
        public void onError(final WebSocketSession s, final Exception ex) {
            fail(ex);
        }
    }

    static final class NullSession implements WebSocketSession {
        @Override
        public CompletableFuture<Void> sendText(final CharSequence d, final boolean l) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendBinary(final ByteBuffer d, final boolean l) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendPing(final ByteBuffer d) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendPong(final ByteBuffer d) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> close(final int c, final String r) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
