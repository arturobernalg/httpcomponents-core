/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

public interface WebSocketSession extends AutoCloseable {

    CompletableFuture<Void> sendText(CharSequence data, boolean last);

    CompletableFuture<Void> sendBinary(ByteBuffer data, boolean last);

    CompletableFuture<Void> sendPing(ByteBuffer data);

    CompletableFuture<Void> sendPong(ByteBuffer data);

    CompletableFuture<Void> close(int code, String reason);

    boolean isOpen();

    @Override
    void close();
}