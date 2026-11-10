package com.travelpay.user;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 세션 Redis 메모리가 가득 차면 로그인·로그인이 필요한 요청은 503이지만, 전체 health는 UP으로 남는지 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
        "logging.structured.format.console="
})
class RedisMemoryFullHttpTest {

    private static final String EMAIL = "memory.full@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_memory_full_test")
            .withUsername("travelpay_test")
            .withPassword(UUID.randomUUID().toString())
            .withInitScript("postgres/user-schema.sql")
            .withUrlParam("currentSchema", "user_service");

    // 로컬 세션 Redis와 같은 메모리 정책입니다.
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-bookworm"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--appendonly", "no", "--save", "",
                    "--maxmemory", "128mb", "--maxmemory-policy", "noeviction");

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
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var user = User.register("홍길동", "010-5000-0001", Instant.now());
            entityManager.persist(user);
            entityManager.persist(UserCredentials.createFor(user, EMAIL, passwordEncoder.encode(PASSWORD),
                    Instant.now()));
        });
    }

    @AfterEach
    void restoreMemoryLimit() throws Exception {
        setMaxMemory("128mb");
    }

    @Test
    void aFullSessionRedisRejectsLoginsAndSessionRequestsWhileHealthStaysUp() throws Exception {
        var session = sessionCookie(login(csrf()));
        var csrfBeforeFull = csrf();

        // 쓰는 명령만 거절되도록 메모리 한도를 지금 사용량보다 낮춥니다.
        setMaxMemory("1");

        assertError(send(HttpRequest.newBuilder(uri("/internal/v1/auth/session")).header("Cookie", session)
                .header("Original-Method", "GET").POST(HttpRequest.BodyPublishers.noBody())), 503);
        assertError(login(csrfBeforeFull), 503);
        var health = send(HttpRequest.newBuilder(uri("/actuator/health")).GET());
        assertEquals(200, health.statusCode());
        assertEquals("UP", mapper.readTree(health.body()).get("status").asString());
    }

    private void setMaxMemory(String limit) throws Exception {
        var result = REDIS.execInContainer("redis-cli", "--no-auth-warning", "-a", REDIS_PASSWORD,
                "CONFIG", "SET", "maxmemory", limit);
        assertEquals("OK", result.getStdout().strip());
    }

    private Csrf csrf() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET());
        assertEquals(200, response.statusCode());
        var data = mapper.readTree(response.body()).get("data");
        return new Csrf(sessionCookie(response), data.get("headerName").asString(), data.get("token").asString());
    }

    private HttpResponse<String> login(Csrf csrf) throws Exception {
        var body = mapper.createObjectNode().put("loginEmail", EMAIL).put("password", PASSWORD);
        return send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Cookie", csrf.cookie()).header(csrf.headerName(), csrf.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode());
        var code = mapper.readTree(response.body()).get("error").get("code").asString();
        assertEquals("COMMON_SERVICE_UNAVAILABLE", code);
    }

    private static String sessionCookie(HttpResponse<String> response) {
        var cookie = response.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream()).filter(value -> value.getName().equals("SESSION"))
                .findFirst().orElseThrow();
        return cookie.getName() + "=" + cookie.getValue();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private record Csrf(String cookie, String headerName, String token) {
    }
}
