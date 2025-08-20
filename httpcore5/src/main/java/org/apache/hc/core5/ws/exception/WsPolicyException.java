/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.exception;

import java.io.IOException;

/**
 * Local policy limits exceeded.
 */
public class WsPolicyException extends IOException {
    public WsPolicyException(final String message) {
        super(message);
    }
}
