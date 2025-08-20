/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.exception;

import java.io.IOException;

/**
 * Protocol violation (RFC 6455).
 */
public class WsProtocolException extends IOException {
    public WsProtocolException(final String message) {
        super(message);
    }

    public WsProtocolException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
