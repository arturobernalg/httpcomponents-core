package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.ws.io.BlockingStreamsChannel;
import org.junit.jupiter.api.Test;

public class BlockingStreamsChannelTest {

    @Test
    void bidirectional_io() throws Exception {
        // Cross-connected pipes
        final PipedInputStream aIn = new PipedInputStream();
        final PipedOutputStream bOut = new PipedOutputStream(aIn);

        final PipedInputStream bIn = new PipedInputStream();
        final PipedOutputStream aOut = new PipedOutputStream(bIn);

        final BlockingStreamsChannel A = new BlockingStreamsChannel(aIn, aOut);
        final BlockingStreamsChannel B = new BlockingStreamsChannel(bIn, bOut);

        final ByteBuffer msg = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
        final int n = A.write(msg);
        assertEquals(5, n);
        final ByteBuffer dst = ByteBuffer.allocate(5);
        final int r = B.read(dst);
        assertEquals(5, r);
        dst.flip();
        final byte[] out = new byte[dst.remaining()];
        dst.get(out);
        assertEquals("hello", new String(out, StandardCharsets.UTF_8));

        A.close();
        B.close();
    }
}
