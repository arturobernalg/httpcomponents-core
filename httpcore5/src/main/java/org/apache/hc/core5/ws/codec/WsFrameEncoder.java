/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.codec;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.ws.WsFrame;
import org.apache.hc.core5.ws.WsOpcode;

/**
 * Minimal RFC&nbsp;6455 frame encoder.
 *
 * <p>Builds a single WebSocket frame from a {@link WsFrame}, applying
 * client-side masking when requested and writing the appropriate payload
 * length field (7/16/64-bit). Validates control-frame constraints and
 * enforces a per-frame payload limit.</p>
 *
 * <p>Not thread-safe. Intended for internal engine use.</p>
 *
 * @since 5.4
 */
@Internal
public final class WsFrameEncoder {

    private final boolean client;           // client frames MUST be masked
    private final long maxFramePayload;

    /**
     * @param client          {@code true} if produced frames are for a client (mask payload),
     *                        {@code false} for a server (no mask)
     * @param maxFramePayload maximum allowed payload size for a single frame (bytes)
     * @since 5.4
     */
    public WsFrameEncoder(final boolean client, final long maxFramePayload) {
        this.client = client;
        this.maxFramePayload = maxFramePayload;
    }

    /**
     * Encodes a frame into a fresh {@link ByteBuffer} ready for writing.
     *
     * <p>For client encoders, a random 32-bit mask key is generated and applied.
     * Control frames are verified to be unfragmented and ≤&nbsp;125&nbsp;bytes.</p>
     *
     * @param frame the frame to encode (payload is read from its current position)
     * @return buffer positioned at start of the encoded frame and limited to its end
     * @throws IllegalArgumentException if control-frame rules are violated or
     *                                  payload exceeds {@code maxFramePayload}
     * @since 5.4
     */
    public ByteBuffer encode(final WsFrame frame) {
        final int len = frame.payload.remaining();
        if (len > maxFramePayload) {
            throw new IllegalArgumentException("Payload too large: " + len);
        }

        if (isControl(frame.opcode)) {
            if (!frame.fin) {
                throw new IllegalArgumentException("Control frames MUST NOT be fragmented");
            }
            if (len > 125) {
                throw new IllegalArgumentException("Control frame payload MUST be <=125 bytes");
            }
        }

        final boolean mask = client;
        final int headerExtra = len <= 125 ? 0 : (len <= 0xFFFF ? 2 : 8);
        final int maskExtra = mask ? 4 : 0;
        final ByteBuffer out = ByteBuffer.allocate(2 + headerExtra + maskExtra + len);

        int b0 = 0;
        if (frame.fin) {
            b0 |= 0x80;
        }
        if (frame.rsv1) {
            b0 |= 0x40;
        }
        if (frame.rsv2) {
            b0 |= 0x20;
        }
        if (frame.rsv3) {
            b0 |= 0x10;
        }
        b0 |= (frame.opcode.code & 0x0F);
        out.put((byte) b0);

        if (len <= 125) {
            out.put((byte) ((mask ? 0x80 : 0x00) | len));
        } else if (len <= 0xFFFF) {
            out.put((byte) ((mask ? 0x80 : 0x00) | 126));
            out.putShort((short) (len & 0xFFFF));
        } else {
            out.put((byte) ((mask ? 0x80 : 0x00) | 127));
            out.putLong((long) len);
        }

        int maskKey = 0;
        if (mask) {
            maskKey = ThreadLocalRandom.current().nextInt();
            out.putInt(maskKey);
        }

        if (len > 0) {
            final ByteBuffer src = frame.payload.slice();
            if (mask) {
                final byte m0 = (byte) (maskKey >>> 24);
                final byte m1 = (byte) (maskKey >>> 16);
                final byte m2 = (byte) (maskKey >>> 8);
                final byte m3 = (byte) (maskKey);
                for (int i = 0; i < len; i++) {
                    final byte p = src.get(i);
                    final byte m = (i & 3) == 0 ? m0 : (i & 3) == 1 ? m1 : (i & 3) == 2 ? m2 : m3;
                    out.put((byte) (p ^ m));
                }
            } else {
                out.put(src);
            }
        }

        out.flip();
        return out;
    }

    private static boolean isControl(final WsOpcode op) {
        return op == WsOpcode.CLOSE || op == WsOpcode.PING || op == WsOpcode.PONG;
    }
}
