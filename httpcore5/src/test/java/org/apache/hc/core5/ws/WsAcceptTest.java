package org.apache.hc.core5.ws;

import org.apache.hc.core5.ws.handshake.WsAccept;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WsAcceptTest {

    @Test
    void computesKnownExample() {
        // RFC example: Sec-WebSocket-Key "dGhlIHNhbXBsZSBub25jZQ=="
        // Accept must be "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        final String key = "dGhlIHNhbXBsZSBub25jZQ==";
        final String expected = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        Assertions.assertEquals(expected, WsAccept.computeAccept(key));
    }
}