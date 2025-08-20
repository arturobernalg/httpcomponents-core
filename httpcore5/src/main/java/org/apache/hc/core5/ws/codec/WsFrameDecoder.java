package org.apache.hc.core5.ws.codec;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.ws.WsFrame;
import org.apache.hc.core5.ws.WsOpcode;
import org.apache.hc.core5.ws.exception.WsPolicyException;
import org.apache.hc.core5.ws.exception.WsProtocolException;

/**
 * Minimal RFC 6455 frame decoder.
 *
 * <p>Appends inbound bytes to an internal buffer and emits {@link WsFrame}
 * instances via {@link #feed(ByteBuffer, Consumer)} whenever a complete frame
 * is available. Validates masking (client frames masked / server frames not),
 * enforces control-frame rules (not fragmented, ≤125 bytes), and checks a
 * per-frame payload limit.</p>
 *
 * <p>Not thread-safe. Intended for internal engine use; surface API may change.</p>
 *
 * @since 5.4
 */
@Internal
public final class WsFrameDecoder {

    private final long maxFramePayload;
    private final boolean expectMasked;

    private ByteBuffer acc = ByteBuffer.allocate(8192);

    /**
     * @param maxFramePayload maximum allowed payload size for a single frame (bytes)
     * @param expectMasked    {@code true} if incoming frames must be masked (server-side),
     *                        {@code false} if they must be unmasked (client-side)
     * @since 5.4
     */
    public WsFrameDecoder(final long maxFramePayload, final boolean expectMasked) {
        this.maxFramePayload = maxFramePayload;
        this.expectMasked = expectMasked;
        this.acc.limit(0);
    }

    /**
     * Feeds bytes into the decoder and emits zero or more decoded frames to {@code sink}.
     * Any trailing partial frame is kept internally for the next call.
     *
     * @throws WsProtocolException on RFC 6455 framing violations
     * @throws WsPolicyException   if the frame payload exceeds {@code maxFramePayload}
     * @since 5.4
     */
    public void feed(final ByteBuffer src, final Consumer<WsFrame> sink) throws WsProtocolException, WsPolicyException {
        append(src);
        acc.flip();
        while (true) {
            final int needed = headerBytesNeeded(acc);
            if (needed > 0) {
                acc.compact();
                return;
            }
            final int startPos = acc.position();
            final int b0 = acc.get() & 0xFF;
            final boolean fin = (b0 & 0x80) != 0;
            final boolean rsv1 = (b0 & 0x40) != 0;
            final boolean rsv2 = (b0 & 0x20) != 0;
            final boolean rsv3 = (b0 & 0x10) != 0;
            final WsOpcode opcode = WsOpcode.fromCode(b0 & 0x0F);

            final int b1 = acc.get() & 0xFF;
            final boolean masked = (b1 & 0x80) != 0;
            final long len7 = (b1 & 0x7F);

            if (masked != expectMasked) {
                throw new WsProtocolException(expectMasked ? "Expected masked frame" : "Masked frame not allowed");
            }

            final long payloadLen;
            if (len7 <= 125) {
                payloadLen = len7;
            } else if (len7 == 126) {
                if (acc.remaining() < 2) {
                    acc.position(startPos);
                    acc.compact();
                    return;
                }
                payloadLen = ((acc.get() & 0xFF) << 8) | (acc.get() & 0xFF);
            } else {
                if (acc.remaining() < 8) {
                    acc.position(startPos);
                    acc.compact();
                    return;
                }
                payloadLen = acc.getLong();
                if (payloadLen < 0) {
                    throw new WsProtocolException("Negative length");
                }
            }

            if (payloadLen > maxFramePayload) {
                throw new WsPolicyException("Frame payload exceeds limit: " + payloadLen);
            }

            int maskKey = 0;
            if (masked) {
                if (acc.remaining() < 4) {
                    acc.position(startPos);
                    acc.compact();
                    return;
                }
                maskKey = acc.getInt();
            }

            if (acc.remaining() < payloadLen) {
                acc.position(startPos);
                acc.compact();
                return;
            }

            final int payloadOffset = acc.position();
            final int payloadEnd = (int) (payloadOffset + payloadLen);

            final ByteBuffer payload = acc.slice();
            payload.limit((int) payloadLen);

            if (masked && payloadLen > 0) {
                final byte m0 = (byte) (maskKey >>> 24);
                final byte m1 = (byte) (maskKey >>> 16);
                final byte m2 = (byte) (maskKey >>> 8);
                final byte m3 = (byte) (maskKey);
                for (int i = 0; i < payloadLen; i++) {
                    final byte p = payload.get(i);
                    final byte m = (i & 3) == 0 ? m0 : (i & 3) == 1 ? m1 : (i & 3) == 2 ? m2 : m3;
                    payload.put(i, (byte) (p ^ m));
                }
            }

            // Control frame checks
            if (opcode == WsOpcode.CLOSE || opcode == WsOpcode.PING || opcode == WsOpcode.PONG) {
                if (!fin) {
                    throw new WsProtocolException("Control frames MUST NOT be fragmented");
                }
                if (payloadLen > 125) {
                    throw new WsProtocolException("Control frame payload MUST be <=125 bytes");
                }
            }

            acc.position(payloadEnd);

            sink.accept(new WsFrame(fin, rsv1, rsv2, rsv3, opcode, payload));
            if (!acc.hasRemaining()) {
                acc.compact();
                return;
            }
        }
    }

    private void append(final ByteBuffer src) {
        if (!src.hasRemaining()) {
            return;
        }
        if (acc.capacity() - acc.limit() < src.remaining()) {
            final int newCap = Math.max(acc.capacity() << 1, acc.limit() + src.remaining());
            final ByteBuffer bigger = ByteBuffer.allocate(newCap);
            acc.flip();
            bigger.put(acc);
            bigger.limit(bigger.position());
            acc = bigger;
        }
        acc.position(acc.limit());     // move to end
        acc.limit(acc.capacity());     // open room to capacity
        acc.put(src);                  // append
        acc.limit(acc.position());     // new data length becomes the limit
    }

    private static int headerBytesNeeded(final ByteBuffer buf) {
        final int r = buf.remaining();
        if (r < 2) {
            return 2 - r;
        }
        final int b1 = buf.get(buf.position() + 1) & 0xFF;
        final int len7 = (b1 & 0x7F);
        int need = 2;
        if (len7 == 126) {
            need += 2;
        }
        if (len7 == 127) {
            need += 8;
        }
        if ((b1 & 0x80) != 0) {
            need += 4; // mask key
        }
        return r >= need ? 0 : need - r;
    }
}
