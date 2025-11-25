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
package org.apache.hc.core5.http2.nio.command;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.reactor.Command;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Timeout;

/**
 * HTTP/2 CONNECT tunnel command.
 *
 * @since 5.4
 */
@Internal
public final class H2TunnelCommand implements Command, Cancellable {

    private final HttpHost targetHost;
    private final Timeout timeout;
    private final FutureCallback<ProtocolIOSession> callback;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public H2TunnelCommand(
            final HttpHost targetHost,
            final Timeout timeout,
            final FutureCallback<ProtocolIOSession> callback) {
        this.targetHost = Args.notNull(targetHost, "Target host");
        this.timeout = timeout;
        this.callback = callback;
    }

    public HttpHost getTargetHost() {
        return targetHost;
    }

    public Timeout getTimeout() {
        return timeout;
    }

    public FutureCallback<ProtocolIOSession> getCallback() {
        return callback;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public boolean cancel() {
        if (cancelled.compareAndSet(false, true) && callback != null) {
            callback.cancelled();
        }
        return true;
    }

}
