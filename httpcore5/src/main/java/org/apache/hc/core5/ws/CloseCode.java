/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws;

/**
 * Common close codes (non-exhaustive).
 */
public final class CloseCode {
    private CloseCode() {
    }

    public static final int NORMAL_CLOSURE = 1000;
    public static final int GOING_AWAY = 1001;
    public static final int PROTOCOL_ERROR = 1002;
    public static final int UNSUPPORTED_DATA = 1003;
    public static final int NO_STATUS_RCVD = 1005; // cannot be sent
    public static final int ABNORMAL_CLOSURE = 1006; // cannot be sent
    public static final int INVALID_PAYLOAD = 1007;
    public static final int POLICY_VIOLATION = 1008;
    public static final int MESSAGE_TOO_BIG = 1009;
    public static final int MANDATORY_EXT = 1010;
    public static final int INTERNAL_ERROR = 1011;
}
