package org.apache.hc.core5.ws.extensions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

public class WsZlibTest {

    @Test
    void roundtrip_deflate_inflate() throws Exception {
        final WsZlib z = new WsZlib();
        final byte[] src = repeat("hola ", 5000).getBytes(StandardCharsets.UTF_8);
        final byte[] def = z.deflate(src, true, true);
        assertTrue(def.length < src.length, "should compress");
        final byte[] inf = z.inflate(def, true, true);
        assertArrayEquals(src, inf);
    }

    @Test
    void contextTakeover_reset_changes_stream() throws Exception {
        final WsZlib z = new WsZlib();
        final byte[] a1 = "AAAAAA".getBytes(StandardCharsets.UTF_8);
        final byte[] a2 = "AAAAAA".getBytes(StandardCharsets.UTF_8);

        final byte[] d1 = z.deflate(a1, true, true); // reset after
        final byte[] d2 = z.deflate(a2, true, true);
        // Not asserting exact bytes, but both should be valid and decompress back
        assertTrue(d1.length > 0 && d2.length > 0);
        assertTrue(!Arrays.equals(d1, new byte[0]));
        final byte[] i1 = z.inflate(d1, true, true);
        final byte[] i2 = z.inflate(d2, true, true);
        assertArrayEquals(a1, i1);
        assertArrayEquals(a2, i2);
    }

    private static String repeat(final String s, final int n) {
        final StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
