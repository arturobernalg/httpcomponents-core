/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.extensions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.apache.hc.core5.annotation.Internal;

/**
 * Minimal raw DEFLATE helper for <em>permessage-deflate</em> (RFC&nbsp;7692).
 *
 * <p>Uses {@code nowrap=true} for both {@link Deflater} and {@link Inflater}
 * to produce/consume raw DEFLATE blocks (no zlib header). The encoder performs
 * a {@code SYNC_FLUSH}, strips the RFC&nbsp;7692 tail {@code 00 00 FF FF},
 * and the decoder appends that tail at end-of-message to complete the stream.</p>
 *
 * <p>Not thread-safe. One instance per connection/endpoint.</p>
 *
 * @since 5.4
 */
@Internal
public final class WsZlib {

    private final Deflater deflater; // nowrap=true => raw DEFLATE (no zlib header)
    private final Inflater inflater;

    /**
     * Creates a helper with raw (nowrap) DEFLATE/INFLATE.
     *
     * @since 5.4
     */
    public WsZlib() {
        this.deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        this.inflater = new Inflater(true);
    }

    /**
     * Deflates a complete message chunk using raw DEFLATE with {@code SYNC_FLUSH},
     * then removes the trailing {@code 00 00 FF FF} as mandated by RFC&nbsp;7692.
     *
     * <p>Note: the current implementation always finishes the deflate stream for
     * the given input (independent of {@code finish}) and can optionally reset
     * the compressor when {@code resetAfter} is {@code true}.</p>
     *
     * @param message    uncompressed bytes for a message (or message fragment)
     * @param finish     ignored by this implementation (stream is finished per call)
     * @param resetAfter whether to {@link Deflater#reset()} after producing output
     * @return compressed bytes with the RFC&nbsp;7692 tail removed
     * @since 5.4
     */
    public byte[] deflate(final byte[] message, final boolean finish, final boolean resetAfter) {
        deflater.reset();
        deflater.setInput(message);
        deflater.finish();
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(message.length / 2 + 32);
        final byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            final int n = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            if (n > 0) {
                bos.write(buf, 0, n);
            } else {
                break;
            }
        }
        // Strip RFC 7692 0x00 0x00 0xFF 0xFF tail if present
        byte[] out = bos.toByteArray();
        if (out.length >= 4 &&
                out[out.length - 4] == 0x00 &&
                out[out.length - 3] == 0x00 &&
                (out[out.length - 2] & 0xFF) == 0xFF &&
                (out[out.length - 1] & 0xFF) == 0xFF) {
            final byte[] trimmed = new byte[out.length - 4];
            System.arraycopy(out, 0, trimmed, 0, trimmed.length);
            out = trimmed;
        }
        if (resetAfter) {
            deflater.reset();
        }
        return out;
    }

    /**
     * Inflates compressed frame bytes. When {@code endOfMessage} is {@code true},
     * appends {@code 00 00 FF FF} to complete the raw DEFLATE stream as required
     * by RFC&nbsp;7692 and (optionally) resets the inflater.
     *
     * @param frameData    compressed bytes from one or more frames
     * @param endOfMessage whether this call completes the message
     * @param resetAfter   whether to {@link Inflater#reset()} after EOM
     * @return the inflated bytes
     * @throws Exception if decompression fails
     * @since 5.4
     */
    public byte[] inflate(final byte[] frameData, final boolean endOfMessage, final boolean resetAfter) throws Exception {
        inflater.setInput(frameData);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(frameData.length * 2 + 32);
        final byte[] buf = new byte[8192];
        while (!inflater.needsInput()) {
            final int n = inflater.inflate(buf);
            if (n == 0) {
                break;
            }
            bos.write(buf, 0, n);
        }
        if (endOfMessage) {
            // Per RFC 7692, append a 0x00 0x00 0xFF 0xFF block to complete the stream
            inflater.setInput(new byte[]{0x00, 0x00, (byte) 0xFF, (byte) 0xFF});
            while (true) {
                final int n = inflater.inflate(buf);
                if (n <= 0) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            if (resetAfter) {
                inflater.reset();
            }
        }
        return bos.toByteArray();
    }

    /**
     * Wraps bytes into a flipped {@link ByteBuffer} (position=0, limit=length).
     *
     * @param bytes array to wrap
     * @return a heap {@code ByteBuffer} containing {@code bytes}
     * @since 5.4
     */
    public static ByteBuffer wrap(final byte[] bytes) {
        final ByteBuffer bb = ByteBuffer.allocate(bytes.length);
        bb.put(bytes).flip();
        return bb;
    }
}
