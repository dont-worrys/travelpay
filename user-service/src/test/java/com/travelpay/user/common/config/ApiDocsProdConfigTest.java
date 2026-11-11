package com.travelpay.user.common.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.travelpay.user.UserServiceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 배포 설정 파일(application-prod.yml)만 읽은 앱에서 API 문서와 Swagger UI가 꺼지는지 실제 HTTP로 확인합니다. */
@SpringBootTest(classes = UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 배포와 같은 방식(SPRING_CONFIG_NAME=application-prod, SPRING_PROFILES_ACTIVE=prod)으로 설정 파일을 고릅니다.
        args = {"--spring.config.name=application-prod", "--spring.profiles.active=prod"},
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
                "logging.structured.format.console="
        })
class ApiDocsProdConfigTest {

    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_api_docs_prod_test")
            .withUsername("travelpay_test")
            .withPassword(UUID.randomUUID().toString())
            .withInitScript("postgres/user-schema.sql")
            .withUrlParam("currentSchema", "user_service");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-bookworm"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--appendonly", "no", "--save", "");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Value("${local.server.port}") private int port;
    @Autowired private Environment environment;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void theAppRunsOnTheDeploymentSettings() {
        // 배포 설정 값이 쓰이고, 로컬 설정에만 있는 값은 없어야 합니다(두 파일을 함께 읽지 않음).
        assertEquals("true", environment.getProperty("server.servlet.session.cookie.secure"));
        assertNull(environment.getProperty("springdoc.enable-default-api-docs"));
    }

    // 로컬 설정에서 열리는 주소(ApiDocsHttpTest)가 배포 설정에서는 모두 없어야 합니다.
    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/user", "/v3/api-docs/user-admin",
            "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html"})
    void docsAndSwaggerUiAreOff(String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }
}
