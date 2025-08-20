/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Simple blocking adapter over InputStream/OutputStream.
 */
public final class BlockingStreamsChannel implements DuplexChannel {

    private final InputStream in;
    private final OutputStream out;
    private volatile boolean open = true;

    public BlockingStreamsChannel(final InputStream in, final OutputStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public int read(final ByteBuffer dst) throws IOException {
        if (!open) {
            return -1;
        }
        final int cap = Math.max(1, Math.min(dst.remaining(), 8192));
        final byte[] buf = new byte[cap];
        final int n = in.read(buf);
        if (n <= 0) {
            return n;
        }
        dst.put(buf, 0, n);
        return n;
    }

    @Override
    public int write(final ByteBuffer src) throws IOException {
        if (!open) {
            return 0;
        }
        final int n = Math.min(src.remaining(), 8192);
        final byte[] buf = new byte[n];
        src.get(buf);
        out.write(buf, 0, n);
        return n;
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        open = false;
        try {
            in.close();
        } catch (final IOException ignore) {
        }
        try {
            out.close();
        } catch (final IOException ignore) {
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }
}
