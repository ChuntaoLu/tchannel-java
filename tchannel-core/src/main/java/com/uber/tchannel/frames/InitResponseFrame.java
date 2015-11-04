/*
 * Copyright (c) 2015 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.tchannel.frames;

import java.util.HashMap;
import java.util.Map;

/**
 * Similar to {@link InitRequestFrame}. The initiator requests a version number, and the server responds with the
 * actual version that will be used for the rest of this connection. The header name/values are the same,
 * but identify the server.
 */
public final class InitResponseFrame implements InitFrame {

    private final long id;
    private final int version;
    private final Map<String, String> headers;

    public InitResponseFrame(long id, int version) {
        this.id = id;
        this.version = version;
        this.headers = new HashMap<>();
    }

    public InitResponseFrame(long id, int version, Map<String, String> headers) {
        this.id = id;
        this.version = version;
        this.headers = headers;
    }

    public int getVersion() {
        return version;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public long getId() {
        return this.id;
    }

    public FrameType getMessageType() {
        return FrameType.InitResponse;
    }

    public String getHostPort() {
        return this.headers.get(HOST_PORT_KEY);
    }

    public void setHostPort(String hostPort) { this.headers.put(HOST_PORT_KEY, hostPort); }

    public String getProcessName() {
        return this.headers.get(PROCESS_NAME_KEY);
    }

    public void setProcessName(String processName) {
        this.headers.put(PROCESS_NAME_KEY, processName);
    }

    @Override
    public String toString() {
        return String.format("<%s id=%d version=%d headers=%s>",
                this.getClass().getSimpleName(),
                this.id,
                this.version,
                this.headers
        );
    }
}