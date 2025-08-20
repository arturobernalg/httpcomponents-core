/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.ws.exception.WsPolicyException;
import org.apache.hc.core5.ws.exception.WsProtocolException;

/**
 * Aggregates continuation frames and (optionally) validates UTF-8 across fragments.
 * Emits listener events per fragment (streaming) with correct 'last' flags.
 * <p>
 * When strictUtf8 is true, we maintain a tiny carry-over buffer (<=3 bytes)
 * for incomplete code points at fragment boundaries, so UTF-8 can stream
 * correctly across frames regardless of CharsetDecoder's consumption behavior.
 */
public final class WsMessageAggregator {

    private final boolean strictUtf8;
    private final long maxMessagePayload;

    private boolean inMessage;
    private WsOpcode messageType; // TEXT or BINARY
    private long messageSoFar;

    // Decoder shared across a TEXT message; only reset at start-of-message.
    private final CharsetDecoder utf8 = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    // Carry bytes for an incomplete UTF-8 sequence at the end of a fragment.
    // Max UTF-8 sequence length is 4, but an incomplete tail can be at most 3.
    private final byte[] carry = new byte[4];
    private int carryLen = 0;

    public WsMessageAggregator(final boolean strictUtf8, final long maxMessagePayload) {
        this.strictUtf8 = strictUtf8;
        this.maxMessagePayload = maxMessagePayload;
    }

    public void onFrame(
            final WsFrame f,
            final WebSocketListener listener,
            final WebSocketSession session) throws WsProtocolException, WsPolicyException {

        final WsOpcode op = f.opcode;

        if (op == WsOpcode.CONTINUATION) {
            if (!inMessage) {
                throw new WsProtocolException("Unexpected continuation");
            }
            messageSoFar += f.payload.remaining();
            if (messageSoFar > maxMessagePayload) {
                throw new WsPolicyException("Message exceeds limit");
            }
            if (messageType == WsOpcode.TEXT && strictUtf8) {
                decodeText(listener, session, f.payload, f.fin);
            } else if (messageType == WsOpcode.TEXT) {
                listener.onText(session, StandardCharsets.UTF_8.decode(f.payload), f.fin);
            } else {
                listener.onBinary(session, f.payload.asReadOnlyBuffer(), f.fin);
            }
            if (f.fin) {
                inMessage = false;
            }
            return;
        }

        if (op == WsOpcode.TEXT || op == WsOpcode.BINARY) {
            if (inMessage) {
                throw new WsProtocolException("New data frame while fragmented message in progress");
            }
            inMessage = !f.fin;
            messageType = op;
            messageSoFar = f.payload.remaining();
            if (messageSoFar > maxMessagePayload) {
                throw new WsPolicyException("Message exceeds limit");
            }
            if (op == WsOpcode.TEXT && strictUtf8) {
                // Start of a new TEXT message
                utf8.reset();
                carryLen = 0;
                decodeText(listener, session, f.payload, f.fin);
            } else if (op == WsOpcode.TEXT) {
                listener.onText(session, StandardCharsets.UTF_8.decode(f.payload), f.fin);
            } else {
                listener.onBinary(session, f.payload.asReadOnlyBuffer(), f.fin);
            }
            return;
        }
        // Non-data frames are handled by the engine.
    }

    private void decodeText(
            final WebSocketListener listener,
            final WebSocketSession session,
            final ByteBuffer bytes,
            final boolean fin) throws WsProtocolException {

        try {
            // Compose input as: [carry][bytes]
            final ByteBuffer input;
            if (carryLen > 0) {
                input = ByteBuffer.allocate(carryLen + bytes.remaining());
                input.put(carry, 0, carryLen);
                input.put(bytes.slice());
                input.flip();
            } else {
                input = bytes.slice();
            }

            // Decode streaming; out size is conservative.
            final CharBuffer out = CharBuffer.allocate(Math.max(64, input.remaining()));
            final CoderResult cr = utf8.decode(input, out, fin);

            // Save any leftover bytes that decoder chose not to consume (incomplete tail)
            carryLen = 0;
            if (input.hasRemaining()) {
                final int leftover = Math.min(input.remaining(), 3); // at most 3 bytes can be incomplete
                for (int i = 0; i < leftover; i++) {
                    carry[i] = input.get();
                }
                carryLen = leftover;
            }

            if (fin) {
                final CoderResult cr2 = utf8.flush(out);
                if (cr2.isError()) {
                    throw new CharacterCodingException();
                }
                // At end of message there must be no incomplete tail.
                if (carryLen != 0) {
                    throw new CharacterCodingException();
                }
            }

            if (cr.isError()) {
                throw new CharacterCodingException();
            }

            out.flip();
            // Emit only if we actually decoded chars; empty chunks are harmless but noisy.
            if (out.hasRemaining()) {
                listener.onText(session, out, fin);
            } else if (fin) {
                // If final chunk produced no chars (e.g., message ended exactly at a codepoint boundary),
                // still signal completion with empty data to satisfy streaming semantics.
                listener.onText(session, CharBuffer.allocate(0), true);
            }
        } catch (final CharacterCodingException ex) {
            throw new WsProtocolException("Invalid UTF-8 in text message", ex);
        }
    }
}
