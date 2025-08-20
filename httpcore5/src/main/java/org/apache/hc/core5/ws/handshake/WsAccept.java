/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.handshake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * RFC 6455 Sec-WebSocket-Accept = B64(SHA1(Sec-WebSocket-Key + MAGIC)).
 */
public final class WsAccept {
    private static final byte[] MAGIC =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.US_ASCII);

    public static String computeAccept(final String secWebSocketKey) {
        try {
            final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(secWebSocketKey.getBytes(StandardCharsets.ISO_8859_1));
            sha1.update(MAGIC);
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (final Exception e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private WsAccept() {
    }
}
