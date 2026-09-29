package com.itways.assistant.attachment.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal S3-compatible endpoint on 127.0.0.1 for tests: records every
 * request and answers PutObject with 200 and DeleteObject with 204, or with
 * an S3 error document when {@link #failWith(int)} is set. No network access
 * beyond the loopback interface, no Docker.
 */
public final class StubS3Server implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> headers, byte[] body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int failStatus;

    public StubS3Server() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange; InputStream in = exchange.getRequestBody()) {
                Map<String, String> headers = new TreeMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        headers, in.readAllBytes()));
                if (failStatus != 0) {
                    byte[] error = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code>"
                            + "<Message>Access Denied</Message></Error>").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(failStatus, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        exchange.getResponseHeaders().add("ETag", "\"stub-etag\"");
                        exchange.sendResponseHeaders(200, -1);
                    }
                    case "DELETE" -> exchange.sendResponseHeaders(204, -1);
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            }
        });
        server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public void failWith(int status) {
        this.failStatus = status;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
