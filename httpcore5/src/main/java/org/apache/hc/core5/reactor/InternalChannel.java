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

package org.apache.hc.core5.reactor;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;

import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.util.TimeWheel;
import org.apache.hc.core5.util.TimeWheelTimeout;
import org.apache.hc.core5.util.Timeout;

abstract class InternalChannel implements ModalCloseable {

    abstract void onIOEvent(final int ops) throws IOException;

    abstract void onTimeout(Timeout timeout) throws IOException;

    abstract void onException(final Exception cause);

    abstract Timeout getTimeout();

    abstract long getLastEventTime();

    protected final TimeWheel timeWheel;

    public InternalChannel(final TimeWheel timeWheel) {
        this.timeWheel = timeWheel;
    }

    final void handleIOEvent(final int ops) {
        try {
            onIOEvent(ops);
        } catch (final CancelledKeyException ex) {
            close(CloseMode.GRACEFUL);
        } catch (final Exception ex) {
            onException(ex);
            close(CloseMode.GRACEFUL);
        }
    }

    /**
     * Schedules a timeout to occur based on the configured timeout duration, invoking the provided
     * {@code onTimeout} action.
     *
     * @param onTimeout the action to be executed when the timeout occurs
     */
    protected void scheduleTimeout(final Runnable onTimeout) {
        if (!getTimeout().isDisabled()) {
            final TimeWheelTimeout scheduledTimeout = new TimeWheelTimeout(getTimeout().toDuration(), onTimeout);
            timeWheel.add(scheduledTimeout);
        }
    }

}
