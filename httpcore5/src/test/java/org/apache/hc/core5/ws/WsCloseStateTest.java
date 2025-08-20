package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.ws.exception.WsProtocolException;
import org.junit.jupiter.api.Test;

public class WsCloseStateTest {

    @Test
    void build_and_parse_close() throws Exception {
        final WsCloseState st = new WsCloseState();
        final WsFrame f = st.buildCloseFrame(CloseCode.NORMAL_CLOSURE, "done");
        assertEquals(WsOpcode.CLOSE, f.opcode);
        final ByteBuffer p = f.payload.slice();
        final int code = ((p.get() & 0xFF) << 8) | (p.get() & 0xFF);
        final byte[] rest = new byte[p.remaining()];
        p.get(rest);
        assertEquals(CloseCode.NORMAL_CLOSURE, code);
        assertEquals("done", new String(rest, StandardCharsets.UTF_8));

        final WsCloseState recv = new WsCloseState();
        recv.onPeerClose(f);
        assertEquals(CloseCode.NORMAL_CLOSURE, recv.code());
        assertEquals("done", recv.reason());
    }

    @Test
    void invalid_close_payload_length_1_throws() {
        final WsCloseState st = new WsCloseState();
        final ByteBuffer bad = ByteBuffer.allocate(1).put((byte) 1);
        bad.flip();
        final WsFrame f = new WsFrame(true, false, false, false, WsOpcode.CLOSE, bad);
        assertThrows(WsProtocolException.class, () -> st.onPeerClose(f));
    }
}
