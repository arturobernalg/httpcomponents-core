/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
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

package org.apache.http.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

import org.apache.http.HttpInetConnection;
import org.apache.http.impl.io.SocketHttpDataReceiver;
import org.apache.http.impl.io.SocketHttpDataTransmitter;
import org.apache.http.io.HttpDataReceiver;
import org.apache.http.io.HttpDataTransmitter;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

/**
 * Implementation of a client-side HTTP connection that can be bound to a 
 * network Socket in order to receive and transmit data.
 *
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 *
 * @version $Revision$
 * 
 * @since 4.0
 */
public class SocketHttpClientConnection 
        extends AbstractHttpClientConnection implements HttpInetConnection {

    protected volatile boolean open;
    protected Socket socket = null;
    
    public SocketHttpClientConnection() {
        super();
    }
    
    protected void assertNotOpen() {
        if (this.open) {
            throw new IllegalStateException("Connection is already open");
        }
    }
    
    protected void assertOpen() {
        if (!this.open) {
            throw new IllegalStateException("Connection is not open");
        }
    }

    protected void bind(
            final Socket socket, 
            final HttpParams params) throws IOException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket may not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("HTTP parameters may not be null");
        }
        socket.setTcpNoDelay(HttpConnectionParams.getTcpNoDelay(params));
        socket.setSoTimeout(HttpConnectionParams.getSoTimeout(params));
        
        int linger = HttpConnectionParams.getLinger(params);
        if (linger >= 0) {
            socket.setSoLinger(linger > 0, linger);
        }

        this.socket = socket;

        setHttpDataReceiver(createHttpDataReceiver(params));
        setHttpDataTransmitter(createHttpDataTransmitter(params));
        setMaxHeaderCount(params.getIntParameter(
                HttpConnectionParams.MAX_HEADER_COUNT, -1));
        setMaxGarbageLines(params.getIntParameter(
                HttpConnectionParams.MAX_STATUS_LINE_GARBAGE, Integer.MAX_VALUE));
        setResponseFactory(new DefaultHttpResponseFactory());
        
        this.open = true;
    }

    protected HttpDataTransmitter createHttpDataTransmitter(
            final HttpParams params) throws IOException {
        int buffersize = HttpConnectionParams.getSocketBufferSize(params);
        HttpDataTransmitter transmitter = new SocketHttpDataTransmitter(this.socket, buffersize);
        transmitter.reset(params);
        return transmitter;
    }
    
    protected HttpDataReceiver createHttpDataReceiver(
            final HttpParams params) throws IOException {
        int buffersize = HttpConnectionParams.getSocketBufferSize(params);
        HttpDataReceiver receiver =  new SocketHttpDataReceiver(this.socket, buffersize);
        receiver.reset(params);
        return receiver;
    }
    
    public boolean isOpen() {
        return this.open;
    }

    public InetAddress getLocalAddress() {
        if (this.socket != null) {
            return this.socket.getLocalAddress();
        } else {
            return null;
        }
    }

    public int getLocalPort() {
        if (this.socket != null) {
            return this.socket.getLocalPort();
        } else {
            return -1;
        }
    }

    public InetAddress getRemoteAddress() {
        if (this.socket != null) {
            return this.socket.getInetAddress();
        } else {
            return null;
        }
    }

    public int getRemotePort() {
        if (this.socket != null) {
            return this.socket.getPort();
        } else {
            return -1;
        }
    }

    public void setSocketTimeout(int timeout) {
        assertOpen();
        if (this.socket != null) {
            try {
                this.socket.setSoTimeout(timeout);
            } catch (SocketException ignore) {
                // It is not quite clear from the Sun's documentation if there are any 
                // other legitimate cases for a socket exception to be thrown when setting 
                // SO_TIMEOUT besides the socket being already closed
            }
        }
    }
    
    public int getSocketTimeout() {
        if (this.socket != null) {
            try {
                return this.socket.getSoTimeout();
            } catch (SocketException ignore) {
                return -1;
            }
        } else {
            return -1;
        }
    }

    public void shutdown() throws IOException {
        this.open = false;
        Socket tmpsocket = this.socket;
        if (tmpsocket != null) {
            tmpsocket.close();
        }
    }
    
    public void close() throws IOException {
        if (!this.open) {
            return;
        }
        this.open = false;
        doFlush();
        try {
            try {
                this.socket.shutdownOutput();
            } catch (IOException ignore) {
            }
            try {
                this.socket.shutdownInput();
            } catch (IOException ignore) {
            }
        } catch (UnsupportedOperationException ignore) {
            // if one isn't supported, the other one isn't either
        }
        this.socket.close();
    }
    
}
