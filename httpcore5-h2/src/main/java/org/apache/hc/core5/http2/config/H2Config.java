/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.core5.http2.config;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http2.frame.FrameConsts;
import org.apache.hc.core5.util.Args;

/**
 * HTTP/2 protocol configuration.
 *
 * @since 5.0
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class H2Config {

    public static final H2Config DEFAULT = custom().build();
    public static final H2Config INIT = initial().build();

    private final int decoderHeaderTableSize;
    private final int encoderHeaderTableSize;
    private final boolean pushEnabled;
    private final int maxConcurrentStreams;
    private final int initialWindowSize;
    private final int maxFrameSize;
    private final int maxHeaderListSize;
    private final boolean compressionEnabled;
    private final int maxContinuations;

    H2Config(final int decoderHeaderTableSize, final int encoderHeaderTableSize,
             final boolean pushEnabled, final int maxConcurrentStreams,
             final int initialWindowSize, final int maxFrameSize, final int maxHeaderListSize,
             final boolean compressionEnabled, final int maxContinuations) {
        super();
        this.decoderHeaderTableSize = decoderHeaderTableSize;
        this.encoderHeaderTableSize = encoderHeaderTableSize;
        this.pushEnabled = pushEnabled;
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.initialWindowSize = initialWindowSize;
        this.maxFrameSize = maxFrameSize;
        this.maxHeaderListSize = maxHeaderListSize;
        this.compressionEnabled = compressionEnabled;
        this.maxContinuations = maxContinuations;
    }

    /**
     * Returns the decoder header table size. This is the value advertised to the peer
     * via SETTINGS_HEADER_TABLE_SIZE and used for the local HPACK decoder.
     *
     * @since 5.5
     */
    public int getDecoderHeaderTableSize() {
        return decoderHeaderTableSize;
    }

    /**
     * Returns the encoder header table size limit. Peer SETTINGS_HEADER_TABLE_SIZE
     * values are capped by this limit before being applied to the local HPACK encoder.
     *
     * @since 5.5
     */
    public int getEncoderHeaderTableSize() {
        return encoderHeaderTableSize;
    }

    /**
     * Returns the decoder header table size for backward compatibility.
     */
    public int getHeaderTableSize() {
        return decoderHeaderTableSize;
    }

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public int getInitialWindowSize() {
        return initialWindowSize;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public int getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    public int getMaxContinuations() {
        return maxContinuations;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("[decoderHeaderTableSize=").append(this.decoderHeaderTableSize)
                .append(", encoderHeaderTableSize=").append(this.encoderHeaderTableSize)
                .append(", pushEnabled=").append(this.pushEnabled)
                .append(", maxConcurrentStreams=").append(this.maxConcurrentStreams)
                .append(", initialWindowSize=").append(this.initialWindowSize)
                .append(", maxFrameSize=").append(this.maxFrameSize)
                .append(", maxHeaderListSize=").append(this.maxHeaderListSize)
                .append(", compressionEnabled=").append(this.compressionEnabled)
                .append(", maxContinuations=").append(this.maxContinuations)
                .append("]");
        return builder.toString();
    }

    public static H2Config.Builder custom() {
        return new Builder();
    }

    private static final int INIT_HEADER_TABLE_SIZE = 4096;
    private static final boolean INIT_ENABLE_PUSH = true;
    private static final int INIT_MAX_FRAME_SIZE = FrameConsts.MIN_FRAME_SIZE;
    private static final int INIT_WINDOW_SIZE = 65535;
    private static final int INIT_CONCURRENT_STREAM = 250;

    public static H2Config.Builder initial() {
        return new Builder()
                .setHeaderTableSize(INIT_HEADER_TABLE_SIZE)
                .setPushEnabled(INIT_ENABLE_PUSH)
                .setMaxConcurrentStreams(Integer.MAX_VALUE) // no limit
                .setMaxFrameSize(INIT_MAX_FRAME_SIZE)
                .setInitialWindowSize(INIT_WINDOW_SIZE)
                .setMaxHeaderListSize(Integer.MAX_VALUE); // unlimited
    }

    public static H2Config.Builder copy(final H2Config config) {
        Args.notNull(config, "Connection config");
        return new Builder()
                .setDecoderHeaderTableSize(config.getDecoderHeaderTableSize())
                .setEncoderHeaderTableSize(config.getEncoderHeaderTableSize())
                .setPushEnabled(config.isPushEnabled())
                .setMaxConcurrentStreams(config.getMaxConcurrentStreams())
                .setInitialWindowSize(config.getInitialWindowSize())
                .setMaxFrameSize(config.getMaxFrameSize())
                .setMaxHeaderListSize(config.getMaxHeaderListSize())
                .setCompressionEnabled(config.isCompressionEnabled());
    }

    public static class Builder {

        private int decoderHeaderTableSize;
        private int encoderHeaderTableSize;
        private boolean pushEnabled;
        private int maxConcurrentStreams;
        private int initialWindowSize;
        private int maxFrameSize;
        private int maxHeaderListSize;
        private boolean compressionEnabled;
        private int maxContinuations;

        Builder() {
            this.decoderHeaderTableSize = INIT_HEADER_TABLE_SIZE * 2;
            this.encoderHeaderTableSize = INIT_HEADER_TABLE_SIZE * 2;
            this.pushEnabled = INIT_ENABLE_PUSH;
            this.maxConcurrentStreams = INIT_CONCURRENT_STREAM;
            this.initialWindowSize = INIT_WINDOW_SIZE;
            this.maxFrameSize = FrameConsts.MIN_FRAME_SIZE * 4;
            this.maxHeaderListSize = FrameConsts.MAX_FRAME_SIZE;
            this.compressionEnabled = true;
            this.maxContinuations = 100;
        }

        /**
         * Sets both the decoder and encoder header table size.
         */
        public Builder setHeaderTableSize(final int headerTableSize) {
            Args.notNegative(headerTableSize, "Header table size");
            this.decoderHeaderTableSize = headerTableSize;
            this.encoderHeaderTableSize = headerTableSize;
            return this;
        }

        /**
         * Sets the decoder header table size. This value is advertised to the peer
         * via SETTINGS_HEADER_TABLE_SIZE and controls the local HPACK decoder table.
         *
         * @since 5.5
         */
        public Builder setDecoderHeaderTableSize(final int decoderHeaderTableSize) {
            Args.notNegative(decoderHeaderTableSize, "Decoder header table size");
            this.decoderHeaderTableSize = decoderHeaderTableSize;
            return this;
        }

        /**
         * Sets the encoder header table size limit. Peer SETTINGS_HEADER_TABLE_SIZE
         * values are capped by this limit before being applied to the local HPACK
         * encoder.
         *
         * @since 5.5
         */
        public Builder setEncoderHeaderTableSize(final int encoderHeaderTableSize) {
            Args.notNegative(encoderHeaderTableSize, "Encoder header table size");
            this.encoderHeaderTableSize = encoderHeaderTableSize;
            return this;
        }

        public Builder setPushEnabled(final boolean pushEnabled) {
            this.pushEnabled = pushEnabled;
            return this;
        }

        public Builder setMaxConcurrentStreams(final int maxConcurrentStreams) {
            this.maxConcurrentStreams = Args.checkRange(maxConcurrentStreams, 0, Integer.MAX_VALUE, "Max concurrent streams");
            return this;
        }

        public Builder setInitialWindowSize(final int initialWindowSize) {
            this.initialWindowSize = Args.checkRange(initialWindowSize, 0, Integer.MAX_VALUE, "Initial window size");
            return this;
        }

        public Builder setMaxFrameSize(final int maxFrameSize) {
            this.maxFrameSize = Args.checkRange(maxFrameSize, FrameConsts.MIN_FRAME_SIZE, FrameConsts.MAX_FRAME_SIZE,
                    "Invalid max frame size");
            return this;
        }

        public Builder setMaxHeaderListSize(final int maxHeaderListSize) {
            this.maxHeaderListSize = Args.checkRange(maxHeaderListSize, 0, Integer.MAX_VALUE, "Max header list size");
            return this;
        }

        public Builder setCompressionEnabled(final boolean compressionEnabled) {
            this.compressionEnabled = compressionEnabled;
            return this;
        }

        /**
         * Sets max limit on number of continuations.
         * <p>value zero represents no limit</p>
         *
         * @since 5,4
         */
        public Builder setMaxContinuations(final int maxContinuations) {
            Args.notNegative(maxContinuations, "Max continuations");
            this.maxContinuations = maxContinuations;
            return this;
        }

        public H2Config build() {
            return new H2Config(
                    decoderHeaderTableSize,
                    encoderHeaderTableSize,
                    pushEnabled,
                    maxConcurrentStreams,
                    initialWindowSize,
                    maxFrameSize,
                    maxHeaderListSize,
                    compressionEnabled,
                    maxContinuations);
        }

    }

}
