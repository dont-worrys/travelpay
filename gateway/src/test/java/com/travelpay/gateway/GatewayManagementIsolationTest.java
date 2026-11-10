package com.travelpay.gateway;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.travelpay.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 로컬 YAML의 관리 포트 분리·API 차단·Actuator 수집을 실제 HTTP로 확인합니다. */
// RANDOM_PORT는 관리 포트도 0으로 바꿉니다. 내장 포트 차단을 실제 설정처럼 시험하려고 양수 포트를 사용합니다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18084",
        "GATEWAY_MANAGEMENT_PORT=18085",
        "GATEWAY_MANAGEMENT_ADDRESS=127.0.0.1",
        "eureka.client.enabled=false",
        "logging.level.root=WARN"
})
class GatewayManagementIsolationTest {

    private static final StubServer USER = StubServer.start();

    @Value("${local.server.port}")
    private int apiPort;

    @Value("${local.management.port:${local.server.port}}")
    private int managementPort;

    @Value("${logging.file.name:}")
    private String logFile;

    @DynamicPropertySource
    static void userServiceOnAStubServer(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER::uri);
    }

    @BeforeEach
    void reset() {
        USER.reset();
    }

    @AfterAll
    static void stop() {
        USER.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/prometheus"})
    void managementEndpointsAreNotAvailableOnTheApiPort(String path) {
        client(apiPort).get().uri(path).exchange().expectStatus().isNotFound();

        assertTrue(USER.received().isEmpty());
    }

    @Test
    void healthChecksRemainAvailableOnTheManagementPort() {
        client(managementPort).get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");

        assertTrue(USER.received().isEmpty());
    }

    @Test
    void prometheusMetricsRemainAvailableOnTheManagementPort() {
        client(managementPort).get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk().expectBody(String.class)
                .value(body -> assertTrue(body.contains("# HELP") && body.contains("# TYPE")));

        assertTrue(USER.received().isEmpty());
    }

    @Test
    void localRunWritesStructuredExceptionLogsToAServiceFile() throws IOException {
        assertFalse(logFile.isBlank(), "Alloy가 읽을 로그 파일이 필요합니다.");
        var marker = "monitoring-log-verification-" + UUID.randomUUID();
        LoggerFactory.getLogger(getClass()).warn(marker, new IllegalStateException("verification exception"));

        var lines = Files.readAllLines(Path.of(logFile));
        var line = lines.stream().filter(value -> value.contains(marker)).findFirst().orElseThrow();
        var log = JsonMapper.builder().build().readTree(line);

        assertEquals("gateway.log", Path.of(logFile).getFileName().toString());
        assertEquals("gateway", log.path("service").asString());
        assertEquals(marker, log.path("message").asString());
        assertTrue(log.path("stack_trace").asString().contains("verification exception"));
        assertFalse(log.path("@timestamp").asString().isBlank());
    }

    @Test
    void localRunPublishesHttpHistogramBucketsForApplicationRequests() {
        client(apiPort).get().uri("/api/v1/terms").exchange().expectStatus().isOk();

        client(managementPort).get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk().expectBody(String.class)
                .value(body -> assertTrue(body.lines().anyMatch(line ->
                        line.startsWith("http_server_requests_seconds_bucket{")
                                && !line.contains("uri=\"/actuator/")),
                        "p95·p99 계산에 사용할 실제 API 요청의 bucket 지표가 필요합니다."));
    }

    @Test
    void apiRoutesRemainAvailableOnTheApiPort() {
        client(apiPort).get().uri("/api/v1/terms").exchange().expectStatus().isOk();

        assertEquals(1, USER.received("/api/v1/terms").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/terms", "/api/v1/wallets/me"})
    void theManagementPortDoesNotProxyApiRoutes(String path) {
        client(managementPort).get().uri(path).exchange().expectStatus().isNotFound();

        assertTrue(USER.received().isEmpty());
    }

    @Test
    void apiAuthorityHeadersCannotMakeTheManagementPortProxyAnApi() throws IOException {
        var response = raw(managementPort, "api.travelpay.test:" + apiPort, apiPort);

        assertTrue(response.startsWith("HTTP/1.1 404"), response);
        assertTrue(USER.received().isEmpty());
    }

    @Test
    void anExternalHttpsHostDoesNotPreventTheApiPortFromProxying() throws IOException {
        var response = raw(apiPort, "api.travelpay.test:443", managementPort);

        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertEquals(1, USER.received("/api/v1/terms").size());
    }

    private String raw(int destinationPort, String host, int forwardedPort) throws IOException {
        try (var socket = new Socket("127.0.0.1", destinationPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/v1/terms HTTP/1.1\r\nHost: " + host + "\r\n"
                    + "Forwarded: for=203.0.113.99;host=api.travelpay.test:" + forwardedPort + ";proto=https\r\n"
                    + "X-Forwarded-Host: api.travelpay.test:" + forwardedPort + "\r\n"
                    + "X-Forwarded-Port: " + forwardedPort + "\r\nConnection: close\r\n\r\n")
                    .getBytes(ISO_8859_1));
            return new String(socket.getInputStream().readAllBytes(), ISO_8859_1);
        }
    }

    private WebTestClient client(int port) {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }
}
