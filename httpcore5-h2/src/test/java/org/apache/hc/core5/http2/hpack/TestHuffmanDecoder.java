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
import java.util.Random;

import org.apache.hc.core5.util.ByteArrayBuffer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestHuffmanDecoder {

    @Test
    void testRfc7541Examples() throws Exception {
        assertDecoded("f1e3c2e5f23a6ba0ab90f4ff", "www.example.com");
        assertDecoded("a8eb10649cbf", "no-cache");
        assertDecoded("25a849e95ba97d7f", "custom-key");
        assertDecoded("25a849e95bb8e8b4bf", "custom-value");
    }

    @Test
    void testRoundTripAllByteValues() throws Exception {
        final byte[] input = new byte[256];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) i;
        }
        assertRoundTrip(input);
    }

    @Test
    void testRoundTripRandomSequences() throws Exception {
        final Random random = new Random(0x7541L);
        for (int i = 0; i < 2000; i++) {
            final byte[] input = new byte[random.nextInt(1025)];
            random.nextBytes(input);
            assertRoundTrip(input);
        }
    }

    @Test
    void testSameBehaviorAsLegacyDecoder() throws Exception {
        final LegacyHuffmanDecoder legacy = new LegacyHuffmanDecoder(Huffman.CODES, Huffman.LENGTHS);
        final Random random = new Random(0x5a17eL);

        for (int value = 0; value < 256; value++) {
            assertSameResult(legacy, new byte[] {(byte) value});
        }
        for (int i = 0; i < 10000; i++) {
            final byte[] encoded = new byte[random.nextInt(9)];
            random.nextBytes(encoded);
            assertSameResult(legacy, encoded);
        }
    }

    @Test
    void testAppendsToExistingOutput() throws Exception {
        final ByteArrayBuffer output = new ByteArrayBuffer(2);
        output.append('x');
        Huffman.DECODER.decode(output, ByteBuffer.wrap(hex("a8eb10649cbf")));
        Assertions.assertArrayEquals("xno-cache".getBytes(StandardCharsets.US_ASCII), output.toByteArray());
    }

    @Test
    void testGrowsSmallOutputBuffer() throws Exception {
        final byte[] input = createRepeated("aaaaaaaaaaaaaaaa", 512);
        final ByteArrayBuffer output = new ByteArrayBuffer(1);
        Huffman.DECODER.decode(output, ByteBuffer.wrap(encode(input)));
        Assertions.assertArrayEquals(input, output.toByteArray());
    }

    private static void assertSameResult(final LegacyHuffmanDecoder legacy, final byte[] encoded) throws Exception {
        final Result expected = decodeLegacy(legacy, encoded);
        final Result actual = decodeDfa(encoded);
        Assertions.assertEquals(expected.failed, actual.failed);
        Assertions.assertArrayEquals(expected.output, actual.output);
    }

    private static Result decodeLegacy(final LegacyHuffmanDecoder legacy, final byte[] encoded) {
        final ByteArrayBuffer output = new ByteArrayBuffer(Math.max(1, encoded.length));
        try {
            legacy.decode(output, ByteBuffer.wrap(encoded));
            return new Result(false, output.toByteArray());
        } catch (final RuntimeException | HPackException ex) {
            return new Result(true, output.toByteArray());
        }
    }

    private static Result decodeDfa(final byte[] encoded) {
        final ByteArrayBuffer output = new ByteArrayBuffer(Math.max(1, encoded.length));
        try {
            Huffman.DECODER.decode(output, ByteBuffer.wrap(encoded));
            return new Result(false, output.toByteArray());
        } catch (final RuntimeException | HPackException ex) {
            return new Result(true, output.toByteArray());
        }
    }

    private static void assertDecoded(final String encoded, final String expected) throws Exception {
        Assertions.assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), decode(hex(encoded)));
    }

    private static void assertRoundTrip(final byte[] input) throws Exception {
        Assertions.assertArrayEquals(input, decode(encode(input)));
    }

    private static byte[] encode(final byte[] input) {
        final ByteArrayBuffer encoded = new ByteArrayBuffer(Math.max(16, input.length));
        Huffman.ENCODER.encode(encoded, ByteBuffer.wrap(input));
        return encoded.toByteArray();
    }

    private static byte[] decode(final byte[] encoded) throws HPackException {
        final ByteArrayBuffer decoded = new ByteArrayBuffer(Math.max(1, encoded.length));
        Huffman.DECODER.decode(decoded, ByteBuffer.wrap(encoded));
        return decoded.toByteArray();
    }

    private static byte[] createRepeated(final String value, final int length) {
        final StringBuilder buffer = new StringBuilder(length);
        while (buffer.length() < length) {
            buffer.append(value);
        }
        buffer.setLength(length);
        return buffer.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] hex(final String value) {
        final byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            final int offset = i * 2;
            result[i] = (byte) Integer.parseInt(value.substring(offset, offset + 2), 16);
        }
        return result;
    }

    private static final class Result {

        private final boolean failed;
        private final byte[] output;

        Result(final boolean failed, final byte[] output) {
            this.failed = failed;
            this.output = output;
        }
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
