/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.core5.ws;

import java.time.Duration;

/**
 * Limits and policy knobs for the core engine.
 */
public final class WsConfig {

    private final long maxFramePayload;
    private final long maxMessagePayload;
    private final boolean strictTextUtf8;
    private final boolean autoPong;
    private final boolean autoCloseOnProtocolError;
    private final int writeHighWatermark;
    private final int writeLowWatermark;
    private final Duration closeWaitTimeout;

    // --- RFC 7692: permessage-deflate (client-side behavior in core) ---
    private final boolean perMessageDeflateEnabled;
    private final boolean clientNoContextTakeover;
    private final boolean serverNoContextTakeover;
    private final Integer clientMaxWindowBits;
    private final Integer serverMaxWindowBits;

    private WsConfig(
            final long maxFramePayload,
            final long maxMessagePayload,
            final boolean strictTextUtf8,
            final boolean autoPong,
            final boolean autoCloseOnProtocolError,
            final int writeHighWatermark,
            final int writeLowWatermark,
            final Duration closeWaitTimeout,
            final boolean perMessageDeflateEnabled,
            final boolean clientNoContextTakeover,
            final boolean serverNoContextTakeover,
            final Integer clientMaxWindowBits,
            final Integer serverMaxWindowBits) {
        this.maxFramePayload = maxFramePayload;
        this.maxMessagePayload = maxMessagePayload;
        this.strictTextUtf8 = strictTextUtf8;
        this.autoPong = autoPong;
        this.autoCloseOnProtocolError = autoCloseOnProtocolError;
        this.writeHighWatermark = writeHighWatermark;
        this.writeLowWatermark = writeLowWatermark;
        this.closeWaitTimeout = closeWaitTimeout;

        this.perMessageDeflateEnabled = perMessageDeflateEnabled;
        this.clientNoContextTakeover = clientNoContextTakeover;
        this.serverNoContextTakeover = serverNoContextTakeover;
        this.clientMaxWindowBits = clientMaxWindowBits;
        this.serverMaxWindowBits = serverMaxWindowBits;
    }

    public long getMaxFramePayload() {
        return maxFramePayload;
    }

    public long getMaxMessagePayload() {
        return maxMessagePayload;
    }

    public boolean isStrictTextUtf8() {
        return strictTextUtf8;
    }

    public boolean isAutoPong() {
        return autoPong;
    }

    public boolean isAutoCloseOnProtocolError() {
        return autoCloseOnProtocolError;
    }

    public int getWriteHighWatermark() {
        return writeHighWatermark;
    }

    public int getWriteLowWatermark() {
        return writeLowWatermark;
    }

    public Duration getCloseWaitTimeout() {
        return closeWaitTimeout;
    }

    // RFC 7692 getters
    public boolean isPerMessageDeflateEnabled() {
        return perMessageDeflateEnabled;
    }

    public boolean isClientNoContextTakeover() {
        return clientNoContextTakeover;
    }

    public boolean isServerNoContextTakeover() {
        return serverNoContextTakeover;
    }

    public Integer getClientMaxWindowBits() {
        return clientMaxWindowBits;
    }

    public Integer getServerMaxWindowBits() {
        return serverMaxWindowBits;
    }

    public static Builder custom() {
        return new Builder();
    }

    public static final class Builder {
        private long maxFramePayload = 1L << 20; // 1 MiB
        private long maxMessagePayload = 1L << 24; // 16 MiB
        private boolean strictTextUtf8 = true;
        private boolean autoPong = true;
        private boolean autoCloseOnProtocolError = true;
        private int writeHighWatermark = 32;
        private int writeLowWatermark = 16;
        private Duration closeWaitTimeout = Duration.ofSeconds(5);

        private boolean perMessageDeflateEnabled = false;
        private boolean clientNoContextTakeover = false;
        private boolean serverNoContextTakeover = false;
        private Integer clientMaxWindowBits = null;
        private Integer serverMaxWindowBits = null;

        public Builder setMaxFramePayload(final long v) {
            this.maxFramePayload = v;
            return this;
        }

        public Builder setMaxMessagePayload(final long v) {
            this.maxMessagePayload = v;
            return this;
        }

        public Builder setStrictTextUtf8(final boolean v) {
            this.strictTextUtf8 = v;
            return this;
        }

        public Builder setAutoPong(final boolean v) {
            this.autoPong = v;
            return this;
        }

        public Builder setAutoCloseOnProtocolError(final boolean v) {
            this.autoCloseOnProtocolError = v;
            return this;
        }

        public Builder setWriteHighWatermark(final int v) {
            this.writeHighWatermark = v;
            return this;
        }

        public Builder setWriteLowWatermark(final int v) {
            this.writeLowWatermark = v;
            return this;
        }

        public Builder setCloseWaitTimeout(final Duration v) {
            this.closeWaitTimeout = v;
            return this;
        }

        /**
         * Enable RFC 7692 permessage-deflate at the engine level.
         */
        public Builder enablePerMessageDeflate(final boolean on) {
            this.perMessageDeflateEnabled = on;
            return this;
        }

        /**
         * When true, reset deflate state after each message (client context).
         */
        public Builder setClientNoContextTakeover(final boolean on) {
            this.clientNoContextTakeover = on;
            return this;
        }

        /**
         * When true, require/reset inflate state after each inbound message.
         */
        public Builder setServerNoContextTakeover(final boolean on) {
            this.serverNoContextTakeover = on;
            return this;
        }

        /**
         * Negotiation hint only; not enforced by JDK zlib.
         */
        public Builder setClientMaxWindowBits(final Integer bitsOrNull) {
            this.clientMaxWindowBits = bitsOrNull;
            return this;
        }

        /**
         * Negotiation hint only; not enforced by JDK zlib.
         */
        public Builder setServerMaxWindowBits(final Integer bitsOrNull) {
            this.serverMaxWindowBits = bitsOrNull;
            return this;
        }

        public WsConfig build() {
            if (writeLowWatermark > writeHighWatermark) {
                throw new IllegalArgumentException("low watermark > high watermark");
            }
            return new WsConfig(
                    maxFramePayload, maxMessagePayload, strictTextUtf8, autoPong,
                    autoCloseOnProtocolError, writeHighWatermark, writeLowWatermark, closeWaitTimeout,
                    perMessageDeflateEnabled, clientNoContextTakeover, serverNoContextTakeover,
                    clientMaxWindowBits, serverMaxWindowBits);
        }
    }
}
