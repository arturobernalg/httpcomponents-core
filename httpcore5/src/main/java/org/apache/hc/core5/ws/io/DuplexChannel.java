/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.io;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Transport-neutral duplex channel.
 * Implementations may be blocking or non-blocking; partial writes allowed.
 */
public interface DuplexChannel {
    int read(ByteBuffer dst) throws IOException;      // -1 for EOF, 0 if no data (non-blocking)

    int write(ByteBuffer src) throws IOException;     // may write partial; returns bytes written

    void flush() throws IOException;

    void close() throws IOException;

    boolean isOpen();
}
