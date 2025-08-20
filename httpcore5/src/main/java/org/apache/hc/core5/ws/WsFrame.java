/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws;

import java.nio.ByteBuffer;

/**
 * A single WebSocket frame (payload is a slice; not reused after encode).
 */
public final class WsFrame {
    public final boolean fin;
    public final boolean rsv1, rsv2, rsv3;
    public final WsOpcode opcode;
    public final ByteBuffer payload;

    public WsFrame(
            final boolean fin,
            final boolean rsv1,
            final boolean rsv2,
            final boolean rsv3,
            final WsOpcode opcode,
            final ByteBuffer payload) {
        this.fin = fin;
        this.rsv1 = rsv1;
        this.rsv2 = rsv2;
        this.rsv3 = rsv3;
        this.opcode = opcode;
        this.payload = payload == null ? ByteBuffer.allocate(0) : payload;
    }

    public static WsFrame empty(final WsOpcode opcode) {
        return new WsFrame(true, false, false, false, opcode, ByteBuffer.allocate(0));
    }
}
