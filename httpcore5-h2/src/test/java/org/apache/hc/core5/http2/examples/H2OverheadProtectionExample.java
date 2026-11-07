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

package org.apache.hc.core5.http2.examples;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.support.AsyncServerPipeline;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.ListenerEndpoint;

public final class H2OverheadProtectionExample {

    private static final int DATA = 0;
    private static final int HEADERS = 1;
    private static final int SETTINGS = 4;
    private static final int PING = 6;
    private static final int GOAWAY = 7;

    private static final int ACK = 1;
    private static final int END_STREAM = 1;
    private static final int END_HEADERS = 4;

    private static final int BURST = 300;
    private static final int TIMEOUT_MS = 5000;

    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII);

    private H2OverheadProtectionExample() {
    }

    public static void main(final String[] args) throws Exception {

        final HttpAsyncServer server = H2ServerBootstrap.bootstrap()
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                .register("*", AsyncServerPipeline.assemble()
                        .request(Method.GET)
                        .ignoreContent()
                        .response()
                        .asString(ContentType.TEXT_PLAIN)
                        .handle((request, context) ->
                                Message.of(
                                        new BasicHttpResponse(HttpStatus.SC_OK),
                                        "Hello HTTP/2"))
                        .supplier())
                .create();

        server.start();

        try {
            final ListenerEndpoint endpoint = server.listen(
                    new InetSocketAddress(
                            InetAddress.getLoopbackAddress(), 0),
                    URIScheme.HTTP).get(10, TimeUnit.SECONDS);

            final int port =
                    ((InetSocketAddress) endpoint.getAddress()).getPort();

            System.out.println("HTTP/2 listening on localhost:" + port);

            System.out.println("\n=== NORMAL REQUEST ===");
            normalRequest(port);

            System.out.println("\n=== CONTROL FRAME BURST ===");
            final boolean rejected = overheadBurst(port);

            System.out.println("\n=== FRESH CONNECTION ===");
            normalRequest(port);

            System.out.println("\n=== RESULT ===");

            if (rejected) {
                System.out.println("PASS: overhead protection triggered");
            } else {
                throw new AssertionError(
                        "No overhead protection observed after "
                                + BURST + " PING frames");
            }

            System.out.println("PASS: new HTTP/2 connection works");

        } finally {
            server.close(CloseMode.IMMEDIATE);
        }
    }

    private static Socket connect(final int port) throws IOException {

        final Socket socket = new Socket();

        socket.connect(
                new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), port),
                TIMEOUT_MS);

        socket.setSoTimeout(TIMEOUT_MS);
        socket.setTcpNoDelay(true);

        return socket;
    }

    private static void handshake(
            final DataInputStream in,
            final DataOutputStream out) throws IOException {

        out.write(PREFACE);
        writeFrame(out, SETTINGS, 0, 0, new byte[0]);
        out.flush();

        boolean serverSettings = false;

        for (int i = 0; i < 10; i++) {
            final Frame frame = readFrame(in);

            if (frame.type == SETTINGS && frame.flags == 0) {
                writeFrame(out, SETTINGS, ACK, 0, new byte[0]);
                out.flush();
                serverSettings = true;
                break;
            }

            if (frame.type == GOAWAY) {
                throw new IOException("GOAWAY during handshake");
            }
        }

        if (!serverSettings) {
            throw new IOException("Server SETTINGS not received");
        }
    }

    private static void normalRequest(final int port)
            throws IOException {

        try (Socket socket = connect(port)) {
            final DataInputStream in =
                    new DataInputStream(socket.getInputStream());
            final DataOutputStream out =
                    new DataOutputStream(socket.getOutputStream());

            handshake(in, out);

            // HPACK static table:
            // 0x82 -> :method GET
            // 0x86 -> :scheme http
            // 0x84 -> :path /
            // 0x41 -> literal :authority with indexed name
            final ByteArrayOutputStream headers =
                    new ByteArrayOutputStream();

            headers.write(0x82);
            headers.write(0x86);
            headers.write(0x84);
            headers.write(0x41);

            final byte[] authority =
                    "localhost".getBytes(StandardCharsets.US_ASCII);

            headers.write(authority.length);
            headers.write(authority, 0, authority.length);

            writeFrame(out, HEADERS,
                    END_STREAM | END_HEADERS,
                    1, headers.toByteArray());

            out.flush();

            boolean receivedHeaders = false;
            boolean finished = false;

            for (int i = 0; i < 30 && !finished; i++) {
                final Frame frame = readFrame(in);

                if (frame.type == HEADERS && frame.streamId == 1) {
                    receivedHeaders = true;
                }

                if (frame.streamId == 1
                        && (frame.type == HEADERS
                        || frame.type == DATA)
                        && (frame.flags & END_STREAM) != 0) {
                    finished = true;
                }

                if (frame.type == GOAWAY) {
                    throw new IOException("Unexpected GOAWAY");
                }
            }

            if (!receivedHeaders || !finished) {
                throw new IOException("HTTP/2 response incomplete");
            }

            System.out.println("PASS: HTTP/2 request completed");
        }
    }

    private static boolean overheadBurst(final int port)
            throws IOException {

        try (Socket socket = connect(port)) {
            final DataInputStream in =
                    new DataInputStream(socket.getInputStream());
            final DataOutputStream out =
                    new DataOutputStream(socket.getOutputStream());

            handshake(in, out);

            // First verify a legitimate PING exchange.
            writeFrame(out, PING, 0, 0, pingPayload(0));
            out.flush();

            boolean pingAck = false;

            for (int i = 0; i < 10; i++) {
                final Frame frame = readFrame(in);

                if (frame.type == PING
                        && (frame.flags & ACK) != 0) {
                    pingAck = true;
                    break;
                }
                if (frame.type == GOAWAY) {
                    throw new IOException("GOAWAY on normal PING");
                }
            }

            if (!pingAck) {
                throw new IOException("Normal PING was not acknowledged");
            }

            System.out.println("PASS: normal PING acknowledged");

            // Send a bounded burst, without waiting for each ACK.
            // This is intentionally above the experimental
            // 256-token capacity of the proposed limiter.
            try {
                for (int i = 1; i <= BURST; i++) {
                    writeFrame(out, PING, 0, 0, pingPayload(i));
                }
                out.flush();
            } catch (final IOException ex) {
                System.out.println("Connection closed during burst: " + ex);
                return true;
            }

            int acknowledged = 0;

            for (;;) {
                try {
                    final Frame frame = readFrame(in);

                    if (frame.type == GOAWAY) {
                        System.out.println("GOAWAY received after "
                                + acknowledged + " PING ACKs");
                        return true;
                    }

                    if (frame.type == PING
                            && (frame.flags & ACK) != 0) {
                        acknowledged++;
                    }

                    if (acknowledged >= BURST) {
                        System.out.println("All PINGs acknowledged");
                        return false;
                    }

                } catch (final EOFException ex) {
                    System.out.println(
                            "Server closed connection after "
                                    + acknowledged + " ACKs");
                    return true;

                } catch (final java.net.SocketTimeoutException ex) {
                    throw new IOException(
                            "Timeout waiting for PING ACK or GOAWAY; "
                                    + acknowledged + " ACKs received",
                            ex);
                }
            }
        }
    }

    private static byte[] pingPayload(final int value) {

        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value,
                0, 0, 0, 0
        };
    }

    private static void writeFrame(
            final DataOutputStream out,
            final int type,
            final int flags,
            final int streamId,
            final byte[] payload) throws IOException {

        final int length = payload.length;

        if (length > 16384) {
            throw new IllegalArgumentException("Frame too large");
        }

        out.writeByte(length >>> 16);
        out.writeByte(length >>> 8);
        out.writeByte(length);

        out.writeByte(type);
        out.writeByte(flags);
        out.writeInt(streamId & 0x7fffffff);

        out.write(payload);
    }

    private static Frame readFrame(final DataInputStream in)
            throws IOException {

        final int length =
                (in.readUnsignedByte() << 16)
                        | (in.readUnsignedByte() << 8)
                        | in.readUnsignedByte();

        final int type = in.readUnsignedByte();
        final int flags = in.readUnsignedByte();
        final int streamId = in.readInt() & 0x7fffffff;

        if (length > 16384) {
            throw new IOException("Unexpected large frame: " + length);
        }

        final byte[] payload = new byte[length];
        in.readFully(payload);

        return new Frame(type, flags, streamId);
    }

    private static final class Frame {

        final int type;
        final int flags;
        final int streamId;

        Frame(final int type, final int flags, final int streamId) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
        }
    }
}

