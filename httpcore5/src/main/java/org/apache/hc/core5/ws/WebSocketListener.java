/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws;

import java.nio.ByteBuffer;

/**
 * Application callbacks. All methods are optional.
 */
public interface WebSocketListener {
    default void onOpen(final WebSocketSession session) {
    }

    default void onText(final WebSocketSession session, final CharSequence data, final boolean last) {
    }

    default void onBinary(final WebSocketSession session, final ByteBuffer data, final boolean last) {
    }

    default void onPing(final WebSocketSession session, final ByteBuffer data) {
    }

    default void onPong(final WebSocketSession session, final ByteBuffer data) {
    }

    default void onClose(final WebSocketSession session, final int code, final String reason) {
    }

    default void onError(final WebSocketSession session, final Exception ex) {
    }
}
