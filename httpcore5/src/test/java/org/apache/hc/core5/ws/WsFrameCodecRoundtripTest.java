package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.core5.ws.codec.WsFrameDecoder;
import org.apache.hc.core5.ws.codec.WsFrameEncoder;
import org.junit.jupiter.api.Test;

public class WsFrameCodecRoundtripTest {

    private static List<WsFrame> decodeAll(final byte[] bytes, final boolean expectMasked) throws Exception {
        final WsFrameDecoder dec = new WsFrameDecoder(1 << 26, expectMasked);
        final List<WsFrame> out = new ArrayList<>();
        dec.feed(ByteBuffer.wrap(bytes), out::add);
        return out;
    }

    @Test
    void clientMasked_text_to_server_unmasked() throws Exception {
        final WsFrameEncoder encClient = new WsFrameEncoder(true, 1 << 26);
        final ByteBuffer wire = encClient.encode(new WsFrame(true, false, false, false, WsOpcode.TEXT,
                StandardCharsets.UTF_8.encode("Hi")));
        final List<WsFrame> frames = decodeAll(toArray(wire), /*expectMasked=*/true);
        assertEquals(1, frames.size());
        assertEquals(WsOpcode.TEXT, frames.get(0).opcode);
        assertEquals("Hi", StandardCharsets.UTF_8.decode(frames.get(0).payload.slice()).toString());
    }

    @Test
    void serverUnmasked_binary_len126_and_127() throws Exception {
        final WsFrameEncoder encServer = new WsFrameEncoder(false, 1 << 26);

        final byte[] b256 = new byte[256];
        for (int i = 0; i < b256.length; i++) b256[i] = (byte) i;
        final ByteBuffer w1 = encServer.encode(new WsFrame(true, false, false, false, WsOpcode.BINARY, ByteBuffer.wrap(b256)));
        final List<WsFrame> f1 = decodeAll(toArray(w1), /*expectMasked=*/false);
        assertEquals(256, f1.get(0).payload.remaining());

        final byte[] b64k = new byte[65536];
        for (int i = 0; i < b64k.length; i++) b64k[i] = (byte) (i & 0xFF);
        final ByteBuffer w2 = encServer.encode(new WsFrame(true, false, false, false, WsOpcode.BINARY, ByteBuffer.wrap(b64k)));
        final List<WsFrame> f2 = decodeAll(toArray(w2), /*expectMasked=*/false);
        assertEquals(65536, f2.get(0).payload.remaining());
    }

    private static byte[] toArray(final ByteBuffer bb) {
        final ByteBuffer b = bb.slice();
        final byte[] a = new byte[b.remaining()];
        b.get(a);
        return a;
    }
}
