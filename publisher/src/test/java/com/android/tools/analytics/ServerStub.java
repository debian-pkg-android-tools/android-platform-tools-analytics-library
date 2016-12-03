/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.analytics;

import com.google.common.base.Charsets;
import com.google.common.util.concurrent.SettableFuture;
import com.google.wireless.android.play.playlog.proto.ClientAnalytics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny webserver used to stub out the Google Analytics server in tests.
 */
public class ServerStub implements HttpHandler, AutoCloseable {
    private final List<Future<ClientAnalytics.LogRequest>> mResults = new ArrayList<>();
    private final InetSocketAddress mAddress;
    private final HttpServer mServer;
    public static final int HTTP_OK = 200;
    public static final int HTTP_BAD_REQUEST = 404;
    public static final int HTTP_INTERNAL_SERVER_ERROR = 500;

    private final AtomicBoolean mNextResponseServerError = new AtomicBoolean(false);

    /**
     * Creates an instance of the webserver and starts listening on an unused port in the ephemeral
     * range.
     */
    public ServerStub() throws IOException {
        mServer = HttpServer.create(new InetSocketAddress(0), 0);
        mServer.createContext("/log", this);
        mServer.setExecutor(Executors.newSingleThreadExecutor());
        mServer.start();

        this.mAddress = mServer.getAddress();
    }

    /**
     * Builds a url for the server stub that can be used in testing {@link AnalyticsPublisher}.
     */
    public URL getUrl() throws MalformedURLException {
        return new URL(String.format("http://localhost:%d/log?format=raw", mAddress.getPort()));
    }

    /**
     * Gets results for calls to this webserver since it was started.
     * The future represents successful (a {@link ClientAnalytics.LogRequest}) and failed
     * (an exception) requests.
     */
    public List<Future<ClientAnalytics.LogRequest>> getResults() {
        // Synchronized to ensure no results are in flight to avoid test flakeyness.
        synchronized (mServer) {
            return mResults;
        }
    }

    /**
     * iff true, instructs the webserver to send an internal server error as the response to the
     * next request made to this server.
     */
    public void makeNextResponseServerError(boolean nextRequestBad) {
        this.mNextResponseServerError.set(nextRequestBad);
    }

    @Override
    public void close() {
        mServer.stop(0);
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        // Synchronized to ensure no results are in flight to avoid test flakeyness.
        synchronized (mServer) {
            if (mNextResponseServerError.get()) {
                byte[] response = "Internal Server Error".getBytes(Charsets.UTF_8);
                httpExchange.sendResponseHeaders(HTTP_INTERNAL_SERVER_ERROR, response.length);
                OutputStream body = httpExchange.getResponseBody();
                body.write(response);
                mNextResponseServerError.set(false);
            } else {
                SettableFuture<ClientAnalytics.LogRequest> data = SettableFuture.create();
                try {
                    data.set(ClientAnalytics.LogRequest.parseFrom(httpExchange.getRequestBody()));
                    httpExchange.sendResponseHeaders(HTTP_OK, 0);
                } catch (IOException e) {
                    byte[] response = "Bad Request".getBytes(Charsets.UTF_8);
                    httpExchange.sendResponseHeaders(HTTP_BAD_REQUEST, response.length);
                    OutputStream body = httpExchange.getResponseBody();
                    body.write(response);
                    data.setException(e);
                }
                mResults.add(data);
            }
        }
    }
}
