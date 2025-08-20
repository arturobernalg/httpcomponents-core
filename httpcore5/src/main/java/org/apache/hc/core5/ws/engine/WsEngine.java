/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.ws.CloseCode;
import org.apache.hc.core5.ws.WebSocketListener;
import org.apache.hc.core5.ws.WebSocketSession;
import org.apache.hc.core5.ws.WsCloseState;
import org.apache.hc.core5.ws.WsConfig;
import org.apache.hc.core5.ws.WsFrame;
import org.apache.hc.core5.ws.WsMessageAggregator;
import org.apache.hc.core5.ws.WsOpcode;
import org.apache.hc.core5.ws.exception.WsPolicyException;
import org.apache.hc.core5.ws.exception.WsProtocolException;
import org.apache.hc.core5.ws.codec.WsFrameDecoder;
import org.apache.hc.core5.ws.codec.WsFrameEncoder;
import org.apache.hc.core5.ws.extensions.WsZlib;
import org.apache.hc.core5.ws.io.DuplexChannel;

/**
 * Minimal WebSocket engine that pumps frames between a {@link DuplexChannel}
 * and an application {@link WebSocketListener}, and exposes a
 * {@link WebSocketSession} for sending.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Read: pull bytes from the transport, decode frames, apply permessage-deflate
 *       (if enabled), aggregate/validate messages, and dispatch callbacks.</li>
 *   <li>Write: encode frames (masking per side), apply permessage-deflate when configured,
 *       and flush to the transport.</li>
 * </ul>
 *
 * <p>Threading: not thread-safe. Drive {@link #onReadable()} and {@link #onWritable()}
 * from a single I/O thread per connection.</p>
 *
 * @since 5.4
 */
@Internal
public final class WsEngine implements AutoCloseable, WebSocketSession {

    private final DuplexChannel transport;
    private final WebSocketListener listener;
    private final WsFrameDecoder decoder;
    private final WsFrameEncoder encoder;
    private final WsMessageAggregator aggregator;
    private final WsCloseState close = new WsCloseState();
    private final ArrayDeque<ByteBuffer> outq = new ArrayDeque<>();
    private final long maxFramePayload;

    private final boolean autoPong;

    // RFC 7692 permessage-deflate state
    private final boolean pmDeflate;
    private final boolean clientNoCtx;
    private final boolean serverNoCtx;
    private final WsZlib zlib = new WsZlib();

    private boolean inboundCompressed = false;   // tracking RSV1 across fragments
    private boolean outboundAccumulating = false;
    private WsOpcode outboundType = null;
    private final List<ByteBuffer> outboundChunks = new ArrayList<>();
    private int outboundBytes = 0;

    // Application-driven fragmentation (uncompressed path)
    private boolean appFragInProgress = false;
    private WsOpcode appFragType = null;

    private volatile boolean open = true;

    /**
     * Creates a new engine bound to a transport and listener.
     *
     * @param transport  full-duplex byte channel
     * @param listener   application callbacks
     * @param config     WebSocket policy/configuration (limits, UTF-8, PMD, etc.)
     * @param clientSide {@code true} if this endpoint behaves as a client (expects unmasked inbound,
     *                   produces masked outbound); {@code false} for server side
     * @since 5.4
     */
    public WsEngine(
            final DuplexChannel transport,
            final WebSocketListener listener,
            final WsConfig config,
            final boolean clientSide) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.maxFramePayload = config.getMaxFramePayload();
        this.autoPong = config.isAutoPong();
        // Client receives unmasked frames; server receives masked frames.
        final boolean expectMaskedOnRx = !clientSide;
        this.decoder = new WsFrameDecoder(config.getMaxFramePayload(), expectMaskedOnRx);
        this.encoder = new WsFrameEncoder(/*client=*/clientSide, config.getMaxFramePayload());
        this.aggregator = new WsMessageAggregator(config.isStrictTextUtf8(), config.getMaxMessagePayload());

        this.pmDeflate = config.isPerMessageDeflateEnabled();
        this.clientNoCtx = config.isClientNoContextTakeover();
        this.serverNoCtx = config.isServerNoContextTakeover();
    }

    /* ---------------- WebSocketSession ---------------- */

    /**
     * Sends a text message fragment.
     *
     * @param data text (UTF-8 encoded before framing)
     * @param last {@code true} if this is the final fragment of the message
     * @return a completed future once enqueued
     * @since 5.4
     */
    @Override
    public CompletableFuture<Void> sendText(final CharSequence data, final boolean last) {
        final ByteBuffer utf8 = StandardCharsets.UTF_8.encode(CharBuffer.wrap(data));
        return sendData(WsOpcode.TEXT, utf8, last);
    }

    /**
     * Sends a binary message fragment.
     *
     * @param data bytes to send (position..limit)
     * @param last {@code true} if this is the final fragment of the message
     * @return a completed future once enqueued
     * @since 5.4
     */
    @Override
    public CompletableFuture<Void> sendBinary(final ByteBuffer data, final boolean last) {
        final ByteBuffer copy = data == null ? ByteBuffer.allocate(0) : data.slice();
        return sendData(WsOpcode.BINARY, copy, last);
    }

    private CompletableFuture<Void> sendData(final WsOpcode type, final ByteBuffer buf, final boolean last) {
        if (!pmDeflate) {
            // Uncompressed path: respect application-driven fragmentation across calls.
            fragmentAndEnqueue(type, buf, last);
            return CompletableFuture.completedFuture(null);
        }
        // Compressed path: accumulate whole message, deflate when last=true, then fragment compressed bytes.
        if (!outboundAccumulating) {
            outboundAccumulating = true;
            outboundType = type;
            outboundChunks.clear();
            outboundBytes = 0;
        } else if (outboundType != type) {
            throw new IllegalStateException("Mixed message types during fragmentation");
        }
        if (buf != null && buf.hasRemaining()) {
            outboundChunks.add(buf.slice());
            outboundBytes += buf.remaining();
        }
        if (last) {
            final byte[] message = new byte[outboundBytes];
            int pos = 0;
            for (final ByteBuffer b : outboundChunks) {
                final int n = b.remaining();
                b.get(message, pos, n);
                pos += n;
            }
            final byte[] compressed = zlib.deflate(message, true, clientNoCtx);
            final ByteBuffer all = WsZlib.wrap(compressed);
            final long max = Math.max(1, maxFramePayload);
            boolean first = true;
            int remaining = all.remaining();
            while (remaining > 0) {
                final int chunk = (int) Math.min(remaining, max);
                final ByteBuffer slice = all.slice();
                slice.limit(chunk);
                all.position(all.position() + chunk);
                remaining -= chunk;
                final boolean fin = remaining == 0;
                final WsOpcode op = first ? outboundType : WsOpcode.CONTINUATION;
                final boolean rsv1 = first; // RFC 7692: RSV1 only on first frame of a compressed message
                first = false;
                enqueueEncoded(new WsFrame(fin, rsv1, false, false, op, slice));
            }
            // reset accumulation
            outboundAccumulating = false;
            outboundType = null;
            outboundChunks.clear();
            outboundBytes = 0;
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Sends a PING control frame.
     *
     * @param data optional payload (≤125 bytes recommended)
     * @return a completed future once enqueued
     * @since 5.4
     */
    @Override
    public CompletableFuture<Void> sendPing(final ByteBuffer data) {
        enqueueEncoded(new WsFrame(true, false, false, false, WsOpcode.PING, data == null ? ByteBuffer.allocate(0) : data.slice()));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Sends a PONG control frame.
     *
     * @param data optional payload (≤125 bytes recommended)
     * @return a completed future once enqueued
     * @since 5.4
     */
    @Override
    public CompletableFuture<Void> sendPong(final ByteBuffer data) {
        enqueueEncoded(new WsFrame(true, false, false, false, WsOpcode.PONG, data == null ? ByteBuffer.allocate(0) : data.slice()));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Initiates the WebSocket close handshake.
     *
     * @param code   close code
     * @param reason optional UTF-8 reason
     * @return a completed future once the CLOSE frame is enqueued
     * @since 5.4
     */
    @Override
    public CompletableFuture<Void> close(final int code, final String reason) {
        enqueueEncoded(close.buildCloseFrame(code, reason));
        // Keep 'open' true so we can read the peer's echoed CLOSE and fire onClose.
        return CompletableFuture.completedFuture(null);
    }

    /**
     * @return {@code true} while the engine/transport is open.
     * @since 5.4
     */
    @Override
    public boolean isOpen() {
        return open && transport.isOpen();
    }

    /**
     * Closes the underlying transport immediately.
     *
     * @since 5.4
     */
    @Override
    public void close() {
        try {
            transport.close();
        } catch (final IOException ignore) {
        }
        open = false;
    }

    /* ---------------- Engine pumping ---------------- */

    /**
     * Read loop step; call when the transport is readable (or in a blocking loop).
     *
     * @throws IOException if the transport read fails
     * @since 5.4
     */
    public void onReadable() throws IOException {
        if (!open) {
            return;
        }
        final ByteBuffer buf = ByteBuffer.allocate(8192);
        int n;
        while ((n = transport.read(buf)) > 0) {
            buf.flip();
            try {
                decoder.feed(buf, this::handleFrame);
            } catch (final WsProtocolException e) {
                protocolError(CloseCode.PROTOCOL_ERROR, e.getMessage());
                break;
            } catch (final WsPolicyException e) {
                protocolError(CloseCode.POLICY_VIOLATION, e.getMessage());
                break;
            }
            buf.compact();
        }
        if (n == -1) {
            open = false;
            listener.onClose(this, close.code(), close.reason());
        }
    }

    /**
     * Write loop step; call when the transport is writable (or periodically).
     *
     * @throws IOException if the transport write fails
     * @since 5.4
     */
    public void onWritable() throws IOException {
        if (!open && outq.isEmpty()) {
            transport.close();
            return;
        }
        while (!outq.isEmpty()) {
            final ByteBuffer head = outq.peekFirst();
            transport.write(head);
            if (head.hasRemaining()) {
                break; // partial; try again later
            }
            outq.removeFirst();
        }
        transport.flush();
    }

    private void handleFrame(final WsFrame f) {
        // ---- RSV validation (RFC 6455 §5.2, §5.5; RFC 7692 §7) ----
        if (f.rsv2 || f.rsv3) {
            protocolError(CloseCode.PROTOCOL_ERROR, "RSV2/RSV3 not negotiated");
            return;
        }
        if ((f.opcode == WsOpcode.PING || f.opcode == WsOpcode.PONG || f.opcode == WsOpcode.CLOSE)
                && (f.rsv1 || f.rsv2 || f.rsv3)) {
            protocolError(CloseCode.PROTOCOL_ERROR, "Control frame with RSV set");
            return;
        }
        if (!pmDeflate
                && (f.opcode == WsOpcode.TEXT || f.opcode == WsOpcode.BINARY || f.opcode == WsOpcode.CONTINUATION)
                && f.rsv1) {
            protocolError(CloseCode.PROTOCOL_ERROR, "RSV1 not negotiated");
            return;
        }
        if (pmDeflate && f.opcode == WsOpcode.CONTINUATION && f.rsv1) {
            protocolError(CloseCode.PROTOCOL_ERROR, "RSV1 on continuation");
            return;
        }

        switch (f.opcode) {
            case TEXT:
            case BINARY:
            case CONTINUATION: {
                WsFrame frame = f;

                // RFC 7692 inbound inflate if negotiated and message is compressed.
                if (pmDeflate) {
                    if (f.opcode == WsOpcode.TEXT || f.opcode == WsOpcode.BINARY) {
                        inboundCompressed = f.rsv1; // start-of-message: RSV1 indicates compression
                    }
                    if (inboundCompressed) {
                        try {
                            final boolean eom = f.fin;
                            final byte[] inflated = zlib.inflate(toBytes(f.payload), eom, serverNoCtx);
                            frame = new WsFrame(f.fin, false, false, false, f.opcode, WsZlib.wrap(inflated));
                            if (eom) {
                                inboundCompressed = false;
                            }
                        } catch (final Exception ex) {
                            protocolError(CloseCode.INVALID_PAYLOAD, "inflate failed");
                            return;
                        }
                    }
                }

                try {
                    aggregator.onFrame(frame, listener, this);
                } catch (final WsProtocolException e) {
                    protocolError(CloseCode.INVALID_PAYLOAD, e.getMessage());
                } catch (final WsPolicyException e) {
                    protocolError(CloseCode.MESSAGE_TOO_BIG, e.getMessage());
                }
                return;
            }

            case PING:
                // Control frame constraints
                if (!f.fin) {
                    protocolError(CloseCode.PROTOCOL_ERROR, "Fragmented control frame");
                    return;
                }
                if (f.payload != null && f.payload.remaining() > 125) {
                    protocolError(CloseCode.PROTOCOL_ERROR, "Control frame too large");
                    return;
                }
                listener.onPing(this, f.payload.asReadOnlyBuffer());
                if (autoPong) {
                    enqueueEncoded(new WsFrame(true, false, false, false, WsOpcode.PONG, f.payload.slice()));
                }
                return;

            case PONG:
                if (!f.fin) {
                    protocolError(CloseCode.PROTOCOL_ERROR, "Fragmented control frame");
                    return;
                }
                if (f.payload != null && f.payload.remaining() > 125) {
                    protocolError(CloseCode.PROTOCOL_ERROR, "Control frame too large");
                    return;
                }
                listener.onPong(this, f.payload.asReadOnlyBuffer());
                return;

            case CLOSE:
                if (!f.fin) {
                    protocolError(CloseCode.PROTOCOL_ERROR, "Fragmented control frame");
                    return;
                }
                if (f.payload != null && f.payload.remaining() > 125) {
                    protocolError(CloseCode.PROTOCOL_ERROR, "Control frame too large");
                    return;
                }
                try {
                    close.onPeerClose(f);
                } catch (final WsProtocolException e) {
                    enqueueEncoded(close.buildCloseFrame(CloseCode.PROTOCOL_ERROR, "bad close"));
                    open = false;
                    listener.onError(this, e);
                    listener.onClose(this, CloseCode.PROTOCOL_ERROR, "bad close");
                    return;
                }
                listener.onClose(this, close.code(), close.reason());
                if (!close.isSent()) {
                    enqueueEncoded(close.buildCloseFrame(close.code(), close.reason()));
                }
                open = false;
                return;

            default:
                protocolError(CloseCode.PROTOCOL_ERROR, "unsupported opcode");
        }
    }

    private void protocolError(final int code, final String reason) {
        enqueueEncoded(close.buildCloseFrame(code, reason));
        open = false;
        listener.onError(this, new WsProtocolException(reason));
        // Ensure tests (and apps) observe closure even without a peer handshake.
        listener.onClose(this, code, reason);
    }

    private void fragmentAndEnqueue(final WsOpcode opcode, final ByteBuffer data, final boolean lastFlag) {
        if (appFragInProgress && opcode != appFragType) {
            throw new IllegalStateException("Mixed message types during application fragmentation");
        }
        final long max = Math.max(1, maxFramePayload);
        final int total = data == null ? 0 : data.remaining();

        // Helper to pick opcode for the first chunk in this call
        final WsOpcode firstOpcode = appFragInProgress ? WsOpcode.CONTINUATION : opcode;

        if (total <= max) {
            final ByteBuffer payload = data == null ? ByteBuffer.allocate(0) : data.slice();
            enqueueEncoded(new WsFrame(lastFlag, false, false, false, firstOpcode, payload));
            // Update application fragmentation state
            if (!appFragInProgress) {
                appFragType = opcode;
            }
            appFragInProgress = !lastFlag;
            if (!appFragInProgress) {
                appFragType = null;
            }
            return;
        }

        // Split into <=max chunks; FIN on last chunk only if lastFlag
        int remaining = total;
        boolean first = true;
        ByteBuffer buf = (data == null ? ByteBuffer.allocate(0) : data);
        if (!appFragInProgress) {
            appFragType = opcode;
        }
        while (remaining > 0) {
            final int chunk = (int) Math.min(remaining, max);
            final ByteBuffer slice = buf.slice();
            slice.limit(chunk);
            buf.position(buf.position() + chunk);
            remaining -= chunk;

            final boolean fin = (remaining == 0) ? lastFlag : false;
            final WsOpcode op = first ? firstOpcode : WsOpcode.CONTINUATION;
            first = false;

            enqueueEncoded(new WsFrame(fin, false, false, false, op, slice));
        }
        appFragInProgress = !lastFlag;
        if (!appFragInProgress) {
            appFragType = null;
        }
    }

    private void enqueueEncoded(final WsFrame frame) {
        final ByteBuffer encoded = encoder.encode(frame);
        outq.add(encoded);
    }

    private static byte[] toBytes(final ByteBuffer buf) {
        final ByteBuffer b = buf.slice();
        final byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }
}
