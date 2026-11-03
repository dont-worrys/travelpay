package com.travelpay.common.feign;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.GlobalExceptionHandler;
import feign.Feign;
import feign.FeignException;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 진짜 HTTP 호출에서 Feign이 만든 예외로 분류를 확인합니다.
 * 시간 초과·연결 실패 때 Feign이 어떤 예외를 주는지는 실제로 호출해 봐야 알 수 있습니다.
 */
class FeignErrorsHttpTest {

    private static final long READ_TIMEOUT_MILLIS = 200;

    private static HttpServer server;
    private static ExecutorService executor;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rejected", exchange -> respond(exchange, 404, """
                {"success":false,"data":null,"error":{"code":"USER_NOT_FOUND","message":"회원을 찾을 수 없습니다.","details":[]}}
                """));
        server.createContext("/unavailable", exchange -> respond(exchange, 503, ""));
        server.createContext("/failed", exchange -> respond(exchange, 500, ""));
        server.createContext("/limited", exchange -> {
            if ("retryAfter=true".equals(exchange.getRequestURI().getRawQuery())) {
                exchange.getResponseHeaders().add("Retry-After", "1");
            }
            respond(exchange, 429, """
                    {"success":false,"data":null,"error":{"code":"COMMON_TOO_MANY_REQUESTS","message":"","details":[]}}
                    """);
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(READ_TIMEOUT_MILLIS * 5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        // 느린 응답이 다음 요청을 막지 않도록 요청마다 다른 스레드에서 처리합니다.
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void aBusinessRejectionIsRejectedAndKeepsItsErrorCode() {
        var exception = assertThrows(FeignException.class, () -> client(serverUrl()).rejected());

        assertTrue(FeignErrors.isRejected(exception));
        assertTrue(FeignErrors.hasErrorCode(exception, "USER_NOT_FOUND"));
        assertEquals(CommonErrorCode.BAD_GATEWAY, FeignErrors.toCommonException(exception).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRateLimitResponseRemains429WithTheCommonJsonBody(boolean retryAfter) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RateLimitProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/test/rate-limit").param("retryAfter", Boolean.toString(retryAfter)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("COMMON_TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    void aRefusedConnectionBecomesServiceUnavailable() throws IOException {
        var exception = assertThrows(FeignException.class, () -> client(closedPortUrl()).rejected());

        assertFalse(FeignErrors.isRejected(exception));
        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, FeignErrors.toCommonException(exception).getErrorCode());
    }

    @Test
    void aServiceUnavailableResponseBecomesServiceUnavailable() {
        var exception = assertThrows(FeignException.class, () -> client(serverUrl()).unavailable());

        assertFalse(FeignErrors.isRejected(exception));
        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, FeignErrors.toCommonException(exception).getErrorCode());
    }

    @Test
    void aServerErrorBecomesBadGateway() {
        var exception = assertThrows(FeignException.class, () -> client(serverUrl()).failed());

        assertFalse(FeignErrors.isRejected(exception));
        assertEquals(CommonErrorCode.BAD_GATEWAY, FeignErrors.toCommonException(exception).getErrorCode());
    }

    @Test
    void aReadTimeoutBecomesAGatewayTimeout() {
        var exception = assertThrows(FeignException.class, () -> client(serverUrl()).slow());

        assertFalse(FeignErrors.isRejected(exception));
        assertEquals(CommonErrorCode.GATEWAY_TIMEOUT, FeignErrors.toCommonException(exception).getErrorCode());
    }

    private static TestApi client(String url) {
        return Feign.builder()
                .options(new Request.Options(1, TimeUnit.SECONDS, READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, true))
                .retryer(Retryer.NEVER_RETRY)
                .target(TestApi.class, url);
    }

    private static String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 잠깐 열었다 닫은 포트로, 연결을 받지 않는 주소를 만듭니다. */
    private static String closedPortUrl() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    interface TestApi {

        @RequestLine("GET /rejected")
        String rejected();

        @RequestLine("GET /unavailable")
        String unavailable();

        @RequestLine("GET /failed")
        String failed();

        @RequestLine("GET /limited?retryAfter={retryAfter}")
        String limited(@Param("retryAfter") boolean retryAfter);

        @RequestLine("GET /slow")
        String slow();
    }

    @RestController
    static class RateLimitProbeController {

        @GetMapping("/test/rate-limit")
        void rateLimit(@RequestParam boolean retryAfter) {
            try {
                client(serverUrl()).limited(retryAfter);
            } catch (FeignException exception) {
                throw FeignErrors.toCommonException(exception);
            }
        }
    }
}
