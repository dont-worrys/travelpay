package com.travelpay.payment.transfer.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.infra.client.user.UserFeignApi;
import com.travelpay.payment.infra.client.wallet.WalletFeignApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 지갑·회원 Feign 클라이언트가 실제 HTTP에서 읽기 타임아웃에 걸리는 시간과 504 변환을 확인합니다.
 * DB·Eureka 없이 Feign만 띄우고, 응답을 일부러 늦추는 로컬 HTTP 서버를 부릅니다.
 * 타임아웃 값은 payment-service의 application.yml(연결 500ms·읽기 1초, 「타임아웃 조정안」 1단계)을 그대로 읽습니다.
 * 송금 가이드 「타임아웃 조정안」의 근거 테스트입니다.
 */
@SpringBootTest(classes = TransferFeignTimeoutTest.FeignOnlyConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "eureka.client.enabled=false")
class TransferFeignTimeoutTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID WALLET_ID = UUID.fromString("0f8e6d4c-2b1a-4f9e-8d7c-6b5a4f3e2d1c");

    private static final HttpServer SERVER = startServer();
    private static volatile long delayMillis;

    @Configuration
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            FeignAutoConfiguration.class})
    @EnableFeignClients(clients = {WalletFeignApi.class, UserFeignApi.class})
    static class FeignOnlyConfig {
    }

    @DynamicPropertySource
    static void stubServer(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + SERVER.getAddress().getPort();
        registry.add("spring.cloud.openfeign.client.config.paymentWalletApi.url", () -> baseUrl);
        registry.add("travelpay.client.user.url", () -> baseUrl);
    }

    @Autowired
    WalletFeignApi walletFeignApi;

    @Autowired
    UserFeignApi userFeignApi;

    @BeforeEach
    void resetDelay() {
        delayMillis = 0;
    }

    @Test
    void walletTimesOutAfterAboutOneSecond() {
        long elapsed = timeoutMillis(() -> new TransferWalletReader(walletFeignApi).findWallet(USER_ID), 2_000);

        assertBetween(elapsed, 900, 1_500);
    }

    @Test
    void userTimesOutAfterAboutOneSecond() {
        long elapsed = timeoutMillis(() -> new TransferUserReader(userFeignApi).findRecipient(USER_ID), 2_000);

        assertBetween(elapsed, 900, 1_500);
    }

    /** 측정 최대(112ms)의 약 3배로 늦은 정상 응답은 1초 안에 받아 그대로 성공합니다. */
    @Test
    void slowButNormalResponsesStillSucceed() {
        delayMillis = 300;

        Optional<WalletResponse> wallet = new TransferWalletReader(walletFeignApi).findWallet(USER_ID);
        Optional<UserRecipientResponse> user =
                new TransferUserReader(userFeignApi).findRecipient(USER_ID);

        assertEquals(WALLET_ID, wallet.orElseThrow().walletId());
        assertEquals(USER_ID, user.orElseThrow().userId());
    }

    private static long timeoutMillis(Runnable call, long serverDelayMillis) {
        delayMillis = serverDelayMillis;
        long start = System.nanoTime();
        BusinessException exception = assertThrows(BusinessException.class, call::run);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertSame(CommonErrorCode.GATEWAY_TIMEOUT, exception.getErrorCode());
        System.out.printf("[timeout] server delay %dms -> 504 after %dms%n", serverDelayMillis, elapsed);
        return elapsed;
    }

    private static void assertBetween(long actual, long min, long max) {
        assertTrue(actual >= min && actual < max, "elapsed " + actual + "ms not in [" + min + ", " + max + ")");
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/v1/wallets", exchange -> respond(exchange, """
                    {"success":true,"data":{"walletId":"%s","status":"ACTIVE"},"error":null}""".formatted(WALLET_ID)));
            server.createContext("/internal/v1/users/", exchange -> respond(exchange, """
                    {"success":true,"data":{"userId":"%s","realName":"홍길동"},"error":null}""".formatted(USER_ID)));
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "timeout-stub");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        try {
            Thread.sleep(delayMillis);
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 연결을 끊은 경우
        } finally {
            exchange.close();
        }
    }
}
