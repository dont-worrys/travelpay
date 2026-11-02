package com.travelpay.gateway.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 테스트에서 다른 서비스 자리에 띄우는 HTTP 서버입니다. 받은 요청을 기록하고, 경로별로 정해 둔 응답을 돌려줍니다. */
public final class StubServer implements AutoCloseable {

    private static final Reply DEFAULT_REPLY = Reply.json(200, """
            {"success":true,"data":{"from":"stub"},"error":null}
            """);

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    private StubServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static StubServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // 느린 응답이 다른 요청을 막지 않도록 요청마다 다른 스레드에서 처리합니다.
            var executor = Executors.newCachedThreadPool();
            var stub = new StubServer(server, executor);
            server.createContext("/", stub::handle);
            server.setExecutor(executor);
            server.start();
            return stub;
        }
        catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reply(String path, Reply reply) {
        replies.put(path, reply);
    }

    public List<Received> received(String path) {
        return received.stream().filter(request -> request.path().equals(path)).toList();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void reset() {
        received.clear();
        replies.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        received.add(new Received(exchange.getRequestMethod(), path, new Headers(exchange.getRequestHeaders())));
        var reply = replies.getOrDefault(path, DEFAULT_REPLY);
        try {
            Thread.sleep(reply.delay());
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        var body = reply.body().getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /** 받은 요청입니다. 헤더 이름은 대소문자를 가리지 않습니다. */
    public record Received(String method, String path, Headers headers) {

        public List<String> header(String name) {
            return headers.getOrDefault(name, List.of());
        }
    }

    public record Reply(int status, String body, Duration delay) {

        public static Reply json(int status, String body) {
            return new Reply(status, body, Duration.ZERO);
        }

        public Reply delayedBy(Duration delay) {
            return new Reply(status, body, delay);
        }
    }
}
