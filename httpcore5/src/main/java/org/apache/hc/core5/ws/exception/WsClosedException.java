/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.exception;

import java.io.IOException;

/**
 * Thrown when operations are attempted on a closed WebSocket.
 */
public class WsClosedException extends IOException {
    private final int code;
    private final String reason;

    public WsClosedException(final int code, final String reason) {
        super("WebSocket closed: " + code + (reason != null && !reason.isEmpty() ? " " + reason : ""));
        this.code = code;
        this.reason = reason;
    }

    public int getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }
}
