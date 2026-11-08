package com.travelpay.user.presentation.controller;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.user.UserServiceApplication;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** 실제 애플리케이션 설정·Flyway 스키마·Redis 세션으로, DB의 세션 버전이 바뀐 뒤 기존 세션이 어떻게 처리되는지 확인합니다. */
// 이 패키지에는 다른 테스트의 전용 설정이 있어서, 실제 애플리케이션 클래스를 직접 지정합니다.
@SpringBootTest(classes = UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
        "logging.structured.format.console="
})
class SessionVersionHttpTest {

    private static final String EMAIL = "session.version@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String NEW_PASSWORD = "TravelpayNew-2026!";
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_session_version_test")
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
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private UUID userId;

    @BeforeEach
    void setUp() {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        userId = new TransactionTemplate(transactionManager).execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var user = User.register("홍길동", "010-1900-0001", Instant.now());
            entityManager.persist(user);
            entityManager.persist(UserCredentials.createFor(user, EMAIL, passwordEncoder.encode(PASSWORD), Instant.now()));
            return user.getId();
        });
    }

    @Test
    void aSessionIsRejectedOnceTheMemberSessionVersionChanges() throws Exception {
        var member = login();
        assertEquals(200, verify(member).statusCode());

        increaseSessionVersion();

        assertError(verify(member), 401, "COMMON_UNAUTHORIZED");
        assertError(send(HttpRequest.newBuilder(uri("/api/v1/users/me")).header("Cookie", member.cookie()).GET()),
                401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void aLoginAfterTheVersionChangeKeepsWorking() throws Exception {
        increaseSessionVersion();

        assertEquals(200, verify(login()).statusCode());
    }

    @Test
    void aSessionWithAnOutdatedVersionCanStillLogOut() throws Exception {
        var member = login();
        increaseSessionVersion();

        var logout = send(HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("Cookie", member.cookie()).header(member.csrfHeader(), member.csrfToken())
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertEquals(204, logout.statusCode());
    }

    @Test
    void suspensionIsReportedBeforeTheVersionMismatchAndTheOldSessionStaysClosedAfterReactivation()
            throws Exception {
        var member = login();
        jdbc.update("update users set status = 'SUSPENDED', session_version = session_version + 1 where id = ?",
                userId);

        assertError(verify(member), 403, "USER_ACCOUNT_SUSPENDED");

        jdbc.update("update users set status = 'ACTIVE' where id = ?", userId);

        assertError(verify(member), 401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void aClosedMemberIsReportedBeforeTheVersionMismatch() throws Exception {
        var member = login();
        jdbc.update("update users set status = 'CLOSED', session_version = session_version + 1 where id = ?", userId);

        assertError(verify(member), 403, "USER_ACCOUNT_CLOSED");
    }

    @Test
    void aPasswordChangeKeepsTheCurrentSessionWhenTheVersionIsNotZero() throws Exception {
        increaseSessionVersion();
        increaseSessionVersion();
        var member = login();
        var body = mapper.createObjectNode().put("currentPassword", PASSWORD).put("newPassword", NEW_PASSWORD);

        var changed = send(HttpRequest.newBuilder(uri("/api/v1/users/me/password"))
                .header("Cookie", member.cookie()).header(member.csrfHeader(), member.csrfToken())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));

        assertEquals(204, changed.statusCode());
        assertEquals(200, verify(new Member(sessionCookie(changed), member.csrfHeader(), member.csrfToken()))
                .statusCode());
    }

    private void increaseSessionVersion() {
        assertEquals(1, jdbc.update("update users set session_version = session_version + 1 where id = ?", userId));
    }

    /** 로그인 후 세션 ID와 CSRF 토큰이 바뀌므로, 로그인한 세션으로 CSRF 토큰을 다시 받습니다. */
    private Member login() throws Exception {
        var anonymous = csrf(null);
        var body = mapper.createObjectNode().put("loginEmail", EMAIL).put("password", PASSWORD);
        var response = send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Cookie", anonymous.cookie()).header(anonymous.csrfHeader(), anonymous.csrfToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        assertEquals(200, response.statusCode());
        return csrf(sessionCookie(response));
    }

    private Member csrf(String cookie) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        var response = send(request);
        assertEquals(200, response.statusCode());
        var data = mapper.readTree(response.body()).get("data");
        var current = cookie == null ? sessionCookie(response) : cookie;
        return new Member(current, data.get("headerName").asString(), data.get("token").asString());
    }

    private HttpResponse<String> verify(Member member) throws Exception {
        return send(HttpRequest.newBuilder(uri("/internal/v1/auth/session"))
                .header("Cookie", member.cookie()).header("Original-Method", "GET")
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertEquals(code, mapper.readTree(response.body()).get("error").get("code").asString());
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

    private record Member(String cookie, String csrfHeader, String csrfToken) {
    }
}
