package com.travelpay.user;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 세션 Redis가 꺼져 있어도 재시작 판단용 liveness는 UP이고, 전체 health만 DOWN인지 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
        "logging.structured.format.console="
})
class HealthProbeWithoutRedisTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_probe_test")
            .withUsername("travelpay_test")
            .withPassword(UUID.randomUUID().toString())
            .withInitScript("postgres/user-schema.sql")
            .withUrlParam("currentSchema", "user_service");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 아무것도 듣지 않는 포트를 Redis 주소로 줘서 Redis가 꺼진 상태를 만듭니다.
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", HealthProbeWithoutRedisTest::closedPort);
        registry.add("spring.data.redis.password", () -> "unused-password");
    }

    @Value("${local.server.port}") private int port;
    @Autowired private JsonMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void livenessStaysUpWhileTheSessionRedisIsDown() throws Exception {
        var liveness = get("/actuator/health/liveness");

        assertEquals(200, liveness.statusCode());
        assertEquals("UP", mapper.readTree(liveness.body()).get("status").asString());
    }

    @Test
    void theOverallHealthReportsTheSessionRedisOutage() throws Exception {
        var health = get("/actuator/health");

        assertEquals(503, health.statusCode());
        assertEquals("DOWN", mapper.readTree(health.body()).get("status").asString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int closedPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
        catch (IOException exception) {
            throw new IllegalStateException("빈 포트를 찾지 못했습니다.", exception);
        }
    }
}
