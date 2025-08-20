/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.ws.exception.WsProtocolException;

/**
 * Tracks close handshake state and helps build/parse CLOSE frames.
 */
public final class WsCloseState {

    private boolean sent;
    private boolean received;
    private int code = CloseCode.ABNORMAL_CLOSURE;
    private String reason = "";

    public boolean isSent() {
        return sent;
    }

    public boolean isReceived() {
        return received;
    }

    public int code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    public boolean isDone() {
        return sent && received;
    }

    public WsFrame buildCloseFrame(final int c, final String r) {
        final byte[] reasonBytes = r == null ? new byte[0] : r.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer pl = ByteBuffer.allocate(2 + reasonBytes.length);
        pl.putShort((short) c);
        pl.put(reasonBytes);
        pl.flip();
        this.sent = true;
        this.code = c;
        this.reason = r == null ? "" : r;
        return new WsFrame(true, false, false, false, WsOpcode.CLOSE, pl);
    }

    public void onPeerClose(final WsFrame f) throws WsProtocolException {
        this.received = true;
        if (f.payload.remaining() == 0) {
            this.code = CloseCode.NORMAL_CLOSURE;
            this.reason = "";
            return;
        }
        if (f.payload.remaining() == 1) {
            throw new WsProtocolException("CLOSE payload invalid length");
        }
        final int c = f.payload.getShort(0) & 0xFFFF;
        if (c == CloseCode.NO_STATUS_RCVD || c == CloseCode.ABNORMAL_CLOSURE) {
            throw new WsProtocolException("Prohibited close code: " + c);
        }
        this.code = c;
        if (f.payload.remaining() > 2) {
            final byte[] rest = new byte[f.payload.remaining() - 2];
            final int oldPos = f.payload.position();
            f.payload.position(2);
            f.payload.get(rest);
            f.payload.position(oldPos);
            this.reason = new String(rest, StandardCharsets.UTF_8);
        } else {
            this.reason = "";
        }
    }
}
