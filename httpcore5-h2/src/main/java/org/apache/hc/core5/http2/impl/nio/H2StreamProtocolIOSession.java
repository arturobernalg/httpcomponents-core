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
package org.apache.hc.core5.http2.impl.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLSession;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.Command;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.reactor.ssl.SSLBufferMode;
import org.apache.hc.core5.reactor.ssl.SSLSessionInitializer;
import org.apache.hc.core5.reactor.ssl.SSLSessionVerifier;
import org.apache.hc.core5.reactor.ssl.TlsDetails;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.apache.hc.core5.util.Timeout;

/**
 * {@link ProtocolIOSession} implementation that presents a single HTTP/2
 * stream as a virtual I/O session to upper protocol layers
 * (for example HTTP/1.1 over an HTTP/2 CONNECT tunnel).
 *
 * <p>HTTP/2 frame I/O (HEADERS / DATA) is handled by a companion
 * {@link H2TunnelStreamHandler}. This class is responsible only for:
 * <ul>
 *   <li>tracking session state and timestamps;</li>
 *   <li>buffering outbound bytes written by {@link #write(ByteBuffer)};</li>
 *   <li>delivering inbound bytes to the current {@link IOEventHandler};</li>
 *   <li>optionally providing a TLS layer on top of the stream when
 *       {@link #startTls(SSLContext, NamedEndpoint, SSLBufferMode,
 *       SSLSessionInitializer, SSLSessionVerifier, Timeout)} is invoked.</li>
 * </ul>
 *
 * @since 5.4
 */
@Internal
@Contract(threading = ThreadingBehavior.SAFE)
public final class H2StreamProtocolIOSession implements ProtocolIOSession, ByteChannel {

    private enum TlsState {
        DISABLED,
        HANDSHAKING,
        ACTIVE,
        CLOSED
    }

    private static final int DEFAULT_BUFFER_SIZE = 8 * 1024;
    private static final int TLS_RECORD_BUFFER = 16 * 1024;

    private final String id;
    private final NamedEndpoint initialEndpoint;
    private final H2StreamChannel streamChannel;
    private final Lock lock;
    private final ByteArrayBuffer outboundBuf;
    private final ByteArrayBuffer inboundEncryptedBuf;
    private final Queue<Command> commandQueue;

    private volatile IOEventHandler handler;
    private volatile IOSession.Status status;
    private volatile boolean open;

    private volatile Timeout socketTimeout;
    private volatile int eventMask;

    private volatile long lastReadTime;
    private volatile long lastWriteTime;
    private volatile long lastEventTime;

    private volatile TlsDetails tlsDetails;

    // TLS state
    private volatile SSLEngine sslEngine;
    private volatile TlsState tlsState;
    private volatile NamedEndpoint tlsEndpoint;
    private volatile SSLSessionVerifier tlsVerifier;

    private volatile boolean connected;

    H2StreamProtocolIOSession(
            final String id,
            final NamedEndpoint initialEndpoint,
            final H2StreamChannel streamChannel) {
        this.id = Args.notBlank(id, "Session id");
        this.initialEndpoint = Args.notNull(initialEndpoint, "Initial endpoint");
        this.streamChannel = Args.notNull(streamChannel, "Stream channel");
        this.lock = new ReentrantLock();
        this.outboundBuf = new ByteArrayBuffer(DEFAULT_BUFFER_SIZE);
        this.inboundEncryptedBuf = new ByteArrayBuffer(DEFAULT_BUFFER_SIZE);
        this.commandQueue = new ConcurrentLinkedQueue<>();
        this.status = IOSession.Status.ACTIVE;
        this.open = true;
        this.socketTimeout = Timeout.ZERO_MILLISECONDS;
        final long now = System.currentTimeMillis();
        this.lastReadTime = now;
        this.lastWriteTime = now;
        this.lastEventTime = now;
        this.eventMask = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
        this.tlsDetails = null;
        this.sslEngine = null;
        this.tlsState = TlsState.DISABLED;
        this.tlsEndpoint = null;
        this.tlsVerifier = null;
        this.connected = false;
    }

    /**
     * Called by the H2 tunnel handler once CONNECT has been
     * successfully established (2xx response).
     */
    void onConnected() throws IOException {
        connected = true;
        final IOEventHandler currentHandler = handler;
        if (currentHandler != null) {
            currentHandler.connected(this);
        }
    }

    /**
     * Called by the H2 tunnel handler when DATA frames arrive
     * on the underlying HTTP/2 stream.
     */
    void onInput(final ByteBuffer src) throws IOException {
        if (src == null || !src.hasRemaining()) {
            return;
        }
        updateReadTime();

        if (tlsState == TlsState.DISABLED) {
            final IOEventHandler currentHandler = handler;
            if (currentHandler != null) {
                currentHandler.inputReady(this, src);
            } else {
                // Consume to advance position
                src.position(src.limit());
            }
            return;
        }

        // TLS-enabled path: treat src as encrypted TLS records.
        final int remaining = src.remaining();
        final byte[] chunk = new byte[remaining];
        src.get(chunk);
        inboundEncryptedBuf.append(chunk, 0, chunk.length);

        processInboundEncrypted();
    }

    /**
     * Called by the H2 tunnel handler when the remote peer
     * half-closes the stream (END_STREAM inbound).
     */
    void onRemoteEndStream() throws IOException {
        final IOEventHandler currentHandler = handler;
        if (currentHandler != null) {
            // Let upper layer know the read side is dead.
            currentHandler.timeout(this, Timeout.ZERO_MILLISECONDS);
        }
    }

    /**
     * Called by the H2 tunnel handler when the stream is terminated.
     */
    void onDisconnected() {
        open = false;
        status = IOSession.Status.CLOSED;
        final IOEventHandler currentHandler = handler;
        if (currentHandler != null) {
            currentHandler.disconnected(this);
        }
    }

    /**
     * Called by the H2 tunnel handler during its output pass to
     * flush any bytes buffered by {@link #write(ByteBuffer)} as
     * HTTP/2 DATA frames.
     *
     * @return {@code true} if any bytes were written; {@code false} otherwise.
     */
    boolean flushToStream() throws IOException {
        lock.lock();
        try {
            if (!open || outboundBuf.isEmpty()) {
                return false;
            }
            final byte[] data = outboundBuf.toByteArray();
            if (data.length == 0) {
                return false;
            }
            final ByteBuffer src = ByteBuffer.wrap(data);
            final int written = streamChannel.write(src);
            if (written > 0) {
                updateWriteTime();
                outboundBuf.clear();
                if (written < data.length) {
                    outboundBuf.append(data, written, data.length - written);
                }
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    boolean hasBufferedOutput() {
        lock.lock();
        try {
            return !outboundBuf.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    void setTlsDetails(final TlsDetails tlsDetails) {
        this.tlsDetails = tlsDetails;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Lock getLock() {
        return lock;
    }

    @Override
    public void enqueue(final Command command, final Command.Priority priority) {
        if (command == null) {
            return;
        }
        // Only a tiny subset of commands is realistically expected here
        // (shutdown / stale check). Anything else can be interpreted by
        // the H2 side or simply cancelled there.
        commandQueue.add(command);
    }

    @Override
    public boolean hasCommands() {
        return !commandQueue.isEmpty();
    }

    @Override
    public Command poll() {
        return commandQueue.poll();
    }

    @Override
    public ByteChannel channel() {
        // From the point of view of upper layers this is a normal
        // ByteChannel. H2TunnelStreamHandler is responsible for
        // calling flushToStream() when it sees OP_WRITE.
        return this;
    }

    @Override
    public SocketAddress getLocalAddress() {
        // Virtual session – no real local address.
        return null;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        // Best effort: expose NamedEndpoint as a socket address.
        return new InetSocketAddress(initialEndpoint.getHostName(), initialEndpoint.getPort());
    }

    @Override
    public int getEventMask() {
        return eventMask;
    }

    @Override
    public void setEventMask(final int ops) {
        this.eventMask = ops;
    }

    @Override
    public void setEvent(final int op) {
        this.eventMask |= op;
    }

    @Override
    public void clearEvent(final int op) {
        this.eventMask &= ~op;
    }

    @Override
    public void close() {
        close(CloseMode.GRACEFUL);
    }

    @Override
    public void close(final CloseMode closeMode) {
        if (!open) {
            return;
        }
        open = false;
        status = IOSession.Status.CLOSED;
        try {
            // Notify TLS engine if present
            final SSLEngine engine = this.sslEngine;
            if (engine != null && tlsState != TlsState.CLOSED) {
                try {
                    engine.closeOutbound();
                } catch (final Exception ignore) {
                }
                tlsState = TlsState.CLOSED;
            }
            streamChannel.markLocalClosed();
        } finally {
            final IOEventHandler currentHandler = handler;
            if (currentHandler != null) {
                currentHandler.disconnected(this);
            }
        }
    }

    @Override
    public IOSession.Status getStatus() {
        return status;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public Timeout getSocketTimeout() {
        return socketTimeout;
    }

    @Override
    public void setSocketTimeout(final Timeout timeout) {
        this.socketTimeout = timeout != null ? timeout : Timeout.ZERO_MILLISECONDS;
    }

    @Override
    public int read(final ByteBuffer dst) {
        // Pull-style reads are not used; the H2 side pushes data via onInput().
        return 0;
    }

    @Override
    public int write(final ByteBuffer src) {
        if (!open || src == null || !src.hasRemaining()) {
            return 0;
        }

        if (tlsState == TlsState.DISABLED) {
            // Plaintext tunnel: just buffer and let flushToStream() send it.
            final int remaining = src.remaining();
            final byte[] chunk = new byte[remaining];
            src.get(chunk);
            lock.lock();
            try {
                outboundBuf.append(chunk, 0, chunk.length);
            } finally {
                lock.unlock();
            }
            updateWriteTime();
            return remaining;
        }

        // TLS-enabled path: wrap application data into TLS records.
        final int originalRemaining = src.remaining();
        try {
            while (src.hasRemaining() && tlsState != TlsState.CLOSED) {
                final ByteBuffer netBuf = ByteBuffer.allocate(TLS_RECORD_BUFFER);
                final SSLEngineResult result = sslEngine.wrap(src, netBuf);
                netBuf.flip();
                if (netBuf.hasRemaining()) {
                    final byte[] data = new byte[netBuf.remaining()];
                    netBuf.get(data);
                    lock.lock();
                    try {
                        outboundBuf.append(data, 0, data.length);
                    } finally {
                        lock.unlock();
                    }
                    updateWriteTime();
                }

                handleHandshakeStatus(result.getHandshakeStatus());

                switch (result.getStatus()) {
                    case OK:
                        // keep looping while src has remaining
                        break;
                    case CLOSED:
                        tlsState = TlsState.CLOSED;
                        return originalRemaining - src.remaining();
                    case BUFFER_OVERFLOW:
                        // Should not happen with fresh buffer; retry with larger buffer if needed.
                        continue;
                    case BUFFER_UNDERFLOW:
                        // Unexpected for wrap; break defensively.
                        return originalRemaining - src.remaining();
                }
            }
        } catch (final IOException ex) {
            failTls(ex);
        }

        return originalRemaining - src.remaining();
    }

    @Override
    public void updateReadTime() {
        final long now = System.currentTimeMillis();
        lastReadTime = now;
        lastEventTime = now;
    }

    @Override
    public void updateWriteTime() {
        final long now = System.currentTimeMillis();
        lastWriteTime = now;
        lastEventTime = now;
    }

    @Override
    public long getLastReadTime() {
        return lastReadTime;
    }

    @Override
    public long getLastWriteTime() {
        return lastWriteTime;
    }

    @Override
    public long getLastEventTime() {
        return lastEventTime;
    }

    @Override
    public IOEventHandler getHandler() {
        return handler;
    }

    @Override
    public void upgrade(final IOEventHandler handler) {
        this.handler = Args.notNull(handler, "I/O event handler");
        // If the tunnel was established before the handler was attached,
        // fire a synthetic connected event now.
        if (connected) {
            try {
                handler.connected(this);
            } catch (final IOException ex) {
                handler.exception(this, ex);
                close(CloseMode.IMMEDIATE);
            }
        }
    }

    @Override
    public String toString() {
        return id + " [h2-stream-session " + initialEndpoint + "]";
    }

    @Override
    public NamedEndpoint getInitialEndpoint() {
        return initialEndpoint;
    }

    @Override
    public void switchProtocol(
            final String protocolId,
            final FutureCallback<ProtocolIOSession> callback) {
        // Not meaningful for a single stream; just complete with this.
        if (callback != null) {
            callback.completed(this);
        }
    }

    @Override
    public void registerProtocol(
            final String protocolId,
            final org.apache.hc.core5.reactor.ProtocolUpgradeHandler upgradeHandler) {
        // No-op – stream-level sessions are not protocol negotiators.
    }

    @Override
    public void startTls(
            final SSLContext sslContext,
            final NamedEndpoint endpoint,
            final SSLBufferMode sslBufferMode,
            final SSLSessionInitializer initializer,
            final SSLSessionVerifier verifier,
            final Timeout handshakeTimeout) {

        Args.notNull(sslContext, "SSL context");

        if (tlsState != TlsState.DISABLED) {
            throw new IllegalStateException("TLS has already been started");
        }

        final NamedEndpoint effectiveEndpoint = endpoint != null ? endpoint : initialEndpoint;

        final SSLEngine engine = sslContext.createSSLEngine(
                effectiveEndpoint.getHostName(),
                effectiveEndpoint.getPort());

        engine.setUseClientMode(true);

        final NamedEndpoint tlsEndpoint = endpoint != null ? endpoint : initialEndpoint;

        if (initializer != null) {
            initializer.initialize(tlsEndpoint, sslEngine);
        }

        this.sslEngine = engine;
        this.tlsEndpoint = effectiveEndpoint;
        this.tlsVerifier = verifier;
        this.tlsState = TlsState.HANDSHAKING;

        try {
            engine.beginHandshake();
            // Kick off handshake by performing any initial NEED_WRAP steps.
            doHandshakeDrive();
        } catch (final IOException ex) {
            failTls(ex);
        }

        // Current implementation ignores handshakeTimeout; the enclosing
        // reactor's socket timeout will still apply at the connection level.
    }

    @Override
    public TlsDetails getTlsDetails() {
        return tlsDetails;
    }

    private void processInboundEncrypted() throws IOException {
        final SSLEngine engine = this.sslEngine;
        if (engine == null || tlsState == TlsState.CLOSED) {
            // Should not normally happen; deliver as plain data just in case.
            final IOEventHandler currentHandler = handler;
            if (currentHandler != null && !inboundEncryptedBuf.isEmpty()) {
                final ByteBuffer plain = ByteBuffer.wrap(
                        inboundEncryptedBuf.array(), 0, inboundEncryptedBuf.length());
                inboundEncryptedBuf.clear();
                currentHandler.inputReady(this, plain);
            } else {
                inboundEncryptedBuf.clear();
            }
            return;
        }

        while (!inboundEncryptedBuf.isEmpty() && tlsState != TlsState.CLOSED) {
            final int len = inboundEncryptedBuf.length();
            final ByteBuffer netBuf = ByteBuffer.wrap(
                    inboundEncryptedBuf.array(), 0, len);
            final ByteBuffer appBuf = ByteBuffer.allocate(Math.max(DEFAULT_BUFFER_SIZE, len * 2));

            final SSLEngineResult result = engine.unwrap(netBuf, appBuf);

            final int consumed = netBuf.position();
            final int remaining = len - consumed;
            if (remaining > 0) {
                System.arraycopy(inboundEncryptedBuf.array(), consumed,
                        inboundEncryptedBuf.array(), 0, remaining);
            }
            inboundEncryptedBuf.setLength(remaining);

            handleHandshakeStatus(result.getHandshakeStatus());

            switch (result.getStatus()) {
                case OK:
                    if (appBuf.position() > 0) {
                        appBuf.flip();
                        final IOEventHandler currentHandler = handler;
                        if (currentHandler != null) {
                            currentHandler.inputReady(this, appBuf);
                        }
                    }
                    break;
                case CLOSED:
                    if (appBuf.position() > 0) {
                        appBuf.flip();
                        final IOEventHandler currentHandler = handler;
                        if (currentHandler != null) {
                            currentHandler.inputReady(this, appBuf);
                        }
                    }
                    tlsState = TlsState.CLOSED;
                    close(CloseMode.GRACEFUL);
                    return;
                case BUFFER_UNDERFLOW:
                    // Need more encrypted data; exit loop.
                    return;
                case BUFFER_OVERFLOW:
                    // Our appBuf was too small; retry with a bigger buffer.
                    // In practice with len*2 we should not hit this often.
                    continue;
            }
        }
    }

    private void doHandshakeDrive() throws IOException {
        final SSLEngine engine = this.sslEngine;
        if (engine == null || tlsState == TlsState.CLOSED) {
            return;
        }

        HandshakeStatus hs = engine.getHandshakeStatus();
        while (tlsState == TlsState.HANDSHAKING) {
            switch (hs) {
                case NEED_TASK:
                    runDelegatedTasks(engine);
                    hs = engine.getHandshakeStatus();
                    break;
                case NEED_WRAP: {
                    final ByteBuffer netBuf = ByteBuffer.allocate(TLS_RECORD_BUFFER);
                    final SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), netBuf);
                    netBuf.flip();
                    if (netBuf.hasRemaining()) {
                        final byte[] data = new byte[netBuf.remaining()];
                        netBuf.get(data);
                        lock.lock();
                        try {
                            outboundBuf.append(data, 0, data.length);
                        } finally {
                            lock.unlock();
                        }
                        updateWriteTime();
                    }
                    hs = result.getHandshakeStatus();
                    handleHandshakeStatus(hs);
                    if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                        tlsState = TlsState.CLOSED;
                        return;
                    }
                    if (hs == HandshakeStatus.NEED_UNWRAP) {
                        // Need peer data; stop here and wait for onInput().
                        return;
                    }
                }
                break;
                case NEED_UNWRAP:
                    // Wait for encrypted input via onInput().
                    return;
                case FINISHED:
                case NOT_HANDSHAKING:
                    // Handshake is done.
                    completeTlsHandshake(engine);
                    return;
            }
        }
    }

    private void handleHandshakeStatus(final HandshakeStatus status) throws IOException {
        if (sslEngine == null || tlsState == TlsState.CLOSED) {
            return;
        }
        switch (status) {
            case NEED_TASK:
                runDelegatedTasks(sslEngine);
                break;
            case NEED_WRAP:
                // Drive outbound side of the handshake.
                doHandshakeDrive();
                break;
            case NEED_UNWRAP:
                // Will be handled by processInboundEncrypted() when more
                // encrypted data arrives.
                break;
            case FINISHED:
            case NOT_HANDSHAKING:
                if (tlsState == TlsState.HANDSHAKING) {
                    completeTlsHandshake(sslEngine);
                }
                break;
        }
    }

    private void runDelegatedTasks(final SSLEngine engine) {
        Runnable task = engine.getDelegatedTask();
        while (task != null) {
            task.run();
            task = engine.getDelegatedTask();
        }
    }

    private void completeTlsHandshake(final SSLEngine engine) throws IOException {
        if (tlsState == TlsState.ACTIVE || tlsState == TlsState.CLOSED) {
            return;
        }
        final SSLSession session = engine.getSession();
        if (tlsVerifier != null && tlsEndpoint != null) {
            tlsDetails = tlsVerifier.verify(tlsEndpoint, sslEngine);
        }
        tlsDetails = new TlsDetails(session, null);
        tlsState = TlsState.ACTIVE;
    }

    private void failTls(final Exception ex) {
        final IOEventHandler currentHandler = handler;
        if (currentHandler != null) {
            currentHandler.exception(this, ex);
        }
        close(CloseMode.IMMEDIATE);
    }

}
