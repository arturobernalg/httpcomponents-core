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
package org.apache.hc.core5.http2.hpack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.util.ByteArrayBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
public class HPackHuffmanDecoderBenchmark {

    @State(Scope.Thread)
    public static class DecoderState {

        @Param({"authority", "user-agent", "cookie", "large"})
        public String dataSet;

        private LegacyHuffmanDecoder legacyDecoder;
        private ByteBuffer source;
        private ByteArrayBuffer output;

        @Setup
        public void setup() {
            final byte[] input = createInput(dataSet);
            final ByteArrayBuffer encoded = new ByteArrayBuffer(input.length);
            Huffman.ENCODER.encode(encoded, ByteBuffer.wrap(input));

            this.legacyDecoder = new LegacyHuffmanDecoder(Huffman.CODES, Huffman.LENGTHS);
            this.source = ByteBuffer.wrap(encoded.toByteArray());
            this.output = new ByteArrayBuffer(input.length);
        }

        private static byte[] createInput(final String dataSet) {
            switch (dataSet) {
                case "authority":
                    return "www.example.com".getBytes(StandardCharsets.US_ASCII);
                case "user-agent":
                    return ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
                            .getBytes(StandardCharsets.US_ASCII);
                case "cookie":
                    return createRepeated("session=0123456789abcdef; theme=light; lang=en; ", 512);
                case "large":
                    return createRepeated("accept=text/html,application/xhtml+xml; cache-control=no-cache; ", 4096);
                default:
                    throw new IllegalArgumentException("Unknown data set: " + dataSet);
            }
        }

        private static byte[] createRepeated(final String value, final int length) {
            final StringBuilder buffer = new StringBuilder(length);
            while (buffer.length() < length) {
                buffer.append(value);
            }
            buffer.setLength(length);
            return buffer.toString().getBytes(StandardCharsets.US_ASCII);
        }
    }

    @Benchmark
    public int decodeLegacy(final DecoderState state) throws HPackException {
        state.source.position(0);
        state.output.clear();
        state.legacyDecoder.decode(state.output, state.source);
        return state.output.length();
    }

    @Benchmark
    public int decodeDfa(final DecoderState state) throws HPackException {
        state.source.position(0);
        state.output.clear();
        Huffman.DECODER.decode(state.output, state.source);
        return state.output.length();
    }

    /** Decoder implementation from HttpCore before the DFA optimization. */
    private static final class LegacyHuffmanDecoder {

        private final LegacyHuffmanNode root;

        LegacyHuffmanDecoder(final int[] codes, final byte[] lengths) {
            this.root = buildTree(codes, lengths);
        }

        void decode(final ByteArrayBuffer out, final ByteBuffer src) throws HPackException {
            LegacyHuffmanNode node = this.root;
            int current = 0;
            int bits = 0;
            while (src.hasRemaining()) {
                final int b = src.get() & 0xff;
                current = (current << 8) | b;
                bits += 8;
                while (bits >= 8) {
                    final int c = (current >>> (bits - 8)) & 0xff;
                    node = node.getChild(c);
                    bits -= node.getBits();
                    if (node.isTerminal()) {
                        if (node.getSymbol() == Huffman.EOS) {
                            throw new HPackException("EOS decoded");
                        }
                        out.append(node.getSymbol());
                        node = root;
                    }
                }
            }
            while (bits > 0) {
                final int c = (current << (8 - bits)) & 0xff;
                node = node.getChild(c);
                if (node.isTerminal() && node.getBits() <= bits) {
                    bits -= node.getBits();
                    out.append(node.getSymbol());
                    node = this.root;
                } else {
                    break;
                }
            }
            final int mask = (1 << bits) - 1;
            if ((current & mask) != mask) {
                throw new HPackException("Invalid padding");
            }
        }

        private static LegacyHuffmanNode buildTree(final int[] codes, final byte[] lengths) {
            final LegacyHuffmanNode root = new LegacyHuffmanNode();
            for (int symbol = 0; symbol < codes.length; symbol++) {
                final int code = codes[symbol];
                int length = lengths[symbol];
                LegacyHuffmanNode current = root;
                while (length > 8) {
                    if (current.isTerminal()) {
                        throw new IllegalStateException("Invalid Huffman code: prefix not unique");
                    }
                    length -= 8;
                    final int i = (code >>> length) & 0xff;
                    if (!current.hasChild(i)) {
                        current.setChild(i, new LegacyHuffmanNode());
                    }
                    current = current.getChild(i);
                }
                final LegacyHuffmanNode terminal = new LegacyHuffmanNode(symbol, length);
                final int shift = 8 - length;
                final int start = (code << shift) & 0xff;
                final int end = 1 << shift;
                for (int i = start; i < start + end; i++) {
                    current.setChild(i, terminal);
                }
            }
            return root;
        }
    }

    private static final class LegacyHuffmanNode {

        private final int symbol;
        private final int bits;
        private final LegacyHuffmanNode[] children;

        LegacyHuffmanNode() {
            this.symbol = 0;
            this.bits = 8;
            this.children = new LegacyHuffmanNode[256];
        }

        LegacyHuffmanNode(final int symbol, final int bits) {
            this.symbol = symbol;
            this.bits = bits;
            this.children = null;
        }

        int getBits() {
            return bits;
        }

        int getSymbol() {
            return symbol;
        }

        boolean hasChild(final int index) {
            return children != null && children[index] != null;
        }

        LegacyHuffmanNode getChild(final int index) {
            return children != null ? children[index] : null;
        }

        void setChild(final int index, final LegacyHuffmanNode child) {
            if (children == null) {
                throw new IllegalStateException("Children nodes must not be null");
            }
            children[index] = child;
        }

        boolean isTerminal() {
            return children == null;
        }
    }

}
