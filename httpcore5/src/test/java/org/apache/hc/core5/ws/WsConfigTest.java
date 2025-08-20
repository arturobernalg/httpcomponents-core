package org.apache.hc.core5.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class WsConfigTest {

    @Test
    void lowWatermark_gt_high_throws() {
        final WsConfig.Builder b = WsConfig.custom().setWriteHighWatermark(10).setWriteLowWatermark(20);
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    void permessage_deflate_flags_roundtrip() {
        final WsConfig cfg = WsConfig.custom()
                .enablePerMessageDeflate(true)
                .setClientNoContextTakeover(true)
                .setServerNoContextTakeover(false)
                .setClientMaxWindowBits(15)
                .build();
        assertTrue(cfg.isPerMessageDeflateEnabled());
        assertTrue(cfg.isClientNoContextTakeover());
        assertFalse(cfg.isServerNoContextTakeover());
        assertEquals(Integer.valueOf(15), cfg.getClientMaxWindowBits());
    }
}
