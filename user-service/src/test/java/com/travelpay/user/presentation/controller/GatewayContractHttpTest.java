package com.travelpay.user.presentation.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.application.service.LoginService;
import com.travelpay.user.common.config.ClockConfiguration;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.config.SessionRedisConfiguration;
import com.travelpay.user.common.security.SecurityConfiguration;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import com.travelpay.user.common.security.session.SessionCurrentUserProvider;
import com.travelpay.user.common.security.session.SessionLoginHandler;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.infrastructure.redis.RedisLoginAttemptLimiter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gateway가 user-service에 기대는 계약을 실제 서버(Tomcat)와 실제 Redis 세션으로 확인합니다.
 * MockMvc는 Tomcat의 Client-Ip 처리와 Spring Session의 쿠키·Redis 저장을 거치지 않아 여기서 따로 봅니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = GatewayContractHttpTest.HttpTestApplication.class,
        args = "--spring.config.name=gateway-contract-test",
        properties = {
                "spring.application.name=user-gateway-contract-test",
                "spring.jpa.open-in-view=false",
                "spring.session.timeout=60m",
                "travelpay.user.session.absolute-timeout=24h",
                "travelpay.user.login-attempt.pair-limit=5",
                "travelpay.user.login-attempt.account-limit=20",
                "travelpay.user.login-attempt.window=10m",
                "travelpay.user.login-attempt.block=20m",
                "spring.session.data.redis.namespace=travelpay:user:session:gateway-contract-test",
                "spring.data.redis.repositories.enabled=false",
                "travelpay.auth.header-context.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.security.user.password=unused-test-framework-password",
                "spring.flyway.enabled=false",
                "logging.level.root=WARN"
        })
class GatewayContractHttpTest {

    private static final String EMAIL = "gateway.member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final String FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";
    private static final String REMOTE_IP_HEADER = "server.tomcat.remoteip.remote-ip-header";
    private static final String MAX_REQUEST_HEADER_SIZE = "server.max-http-request-header-size";

    @Value("${local.server.port}") private int port;
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionRepository<? extends Session> sessionRepository;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private UUID userId;

    /** 실제 설정 파일의 값을 그대로 씁니다. 설정 파일에서 빠지면 아래 IP 테스트가 실패합니다. */
    @DynamicPropertySource
    static void forwardHeadersFromTheApplicationFile(DynamicPropertyRegistry registry) {
        registry.add(FORWARD_HEADERS_STRATEGY, () -> applicationProperty("application.yml", FORWARD_HEADERS_STRATEGY));
        registry.add(REMOTE_IP_HEADER, () -> applicationProperty("application.yml", REMOTE_IP_HEADER));
        registry.add(MAX_REQUEST_HEADER_SIZE, () -> applicationProperty("application.yml", MAX_REQUEST_HEADER_SIZE));
    }

    @BeforeEach
    void setUp() {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        userId = new TransactionTemplate(transactionManager).execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var user = User.register("홍길동", "010-1600-0001", AT);
            entityManager.persist(user);
            entityManager.persist(UserCredentials.createFor(user, EMAIL, passwordEncoder.encode(PASSWORD), AT));
            return user.getId();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void bothApplicationFilesTrustTheClientIpForwardedByTheGateway(String filename) {
        assertEquals("native", applicationProperty(filename, FORWARD_HEADERS_STRATEGY));
        assertEquals(AuthHeaders.CLIENT_IP, applicationProperty(filename, REMOTE_IP_HEADER));
    }

    @Test
    void loginFailuresAreCountedPerForwardedClientIp() throws Exception {
        var csrf = csrf(null);

        for (var attempt = 1; attempt <= 5; attempt++) {
            assertEquals(401, login(csrf, "203.0.113.10", "wrong-password").statusCode(), "attempt " + attempt);
        }

        assertEquals(429, login(csrf, "203.0.113.10", "wrong-password").statusCode());
        assertEquals(401, login(csrf, "203.0.113.20", "wrong-password").statusCode());
    }

    @Test
    void sessionVerificationReadsTheRedisSessionKeepsItAliveAndSendsNoCookie() throws Exception {
        var cookie = loginCookie();
        var sessionId = new String(Base64.getDecoder().decode(cookie.substring("SESSION=".length())), UTF_8);
        var tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        moveLastAccessedTime(sessionRepository, sessionId, tenMinutesAgo);

        var response = verifySession(cookie, "GET", null);

        assertEquals(200, response.statusCode());
        var data = mapper.readTree(response.body()).get("data");
        assertEquals(userId.toString(), data.get("userId").asString());
        assertEquals("USER", data.get("role").asString());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertTrue(sessionRepository.findById(sessionId).getLastAccessedTime().isAfter(tenMinutesAgo));
    }

    @Test
    void aStateChangingRequestIsVerifiedWithTheTokenFromTheCsrfApi() throws Exception {
        var cookie = loginCookie();
        var csrf = csrf(cookie);

        assertEquals(403, verifySession(cookie, "POST", null).statusCode());
        assertEquals(200, verifySession(cookie, "POST", csrf.token()).statusCode());
    }

    @Test
    void withoutACookieItIs401AndNoSessionIsCreated() throws Exception {
        var response = verifySession(null, "POST", null);

        assertEquals(401, response.statusCode());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void gatewaySizedCookiesAndMetadataCanVerifyASession(String originalMethod) throws Exception {
        var sessionCookie = loginCookie();
        var csrf = csrf(sessionCookie);
        var request = withGatewayMetadata(HttpRequest.newBuilder(uri("/internal/v1/auth/session")))
                .header("Cookie", browserCookiesOfLength(sessionCookie, 7950))
                .header("Original-Method", originalMethod)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (originalMethod.equals("POST")) {
            request.header(csrf.headerName(), csrf.token());
        }

        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(userId.toString(), mapper.readTree(response.body()).get("data").get("userId").asString());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    }

    @Test
    void gatewaySizedCookiesAlsoLeaveRoomForPublicLoginMetadata() throws Exception {
        var csrf = csrf(null);
        var body = mapper.createObjectNode().put("loginEmail", EMAIL).put("password", PASSWORD);
        var request = withGatewayMetadata(HttpRequest.newBuilder(uri("/api/v1/auth/login")))
                .header("Cookie", browserCookiesOfLength(csrf.cookie(), 7950))
                .header(csrf.headerName(), csrf.token())
                .header("Client-Ip", "203.0.113.30")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(userId.toString(), mapper.readTree(response.body()).get("data").get("userId").asString());
    }

    @Test
    void aRequestBeyondTheUserHeaderBudgetIsStillRejectedBeforeAuthentication() throws Exception {
        var sessionCookie = loginCookie();
        var request = withGatewayMetadata(HttpRequest.newBuilder(uri("/internal/v1/auth/session")))
                .header("Cookie", browserCookiesOfLength(sessionCookie, 17 * 1024))
                .header("Original-Method", "GET")
                .POST(HttpRequest.BodyPublishers.noBody());

        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    private static HttpRequest.Builder withGatewayMetadata(HttpRequest.Builder request) {
        return request.header("Request-Id", UUID.randomUUID().toString())
                .header("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
    }

    // SESSION 값은 그대로 두고 두 쿠키를 추가해 전체 Cookie 헤더의 크기를 맞춥니다.
    private static String browserCookiesOfLength(String sessionCookie, int length) {
        String first = "; analytics_a=";
        String second = "; analytics_b=";
        int padding = length - sessionCookie.length() - first.length() - second.length();
        return sessionCookie + first + "x".repeat(padding / 2)
                + second + "y".repeat(padding - padding / 2);
    }

    /** Redis에 저장된 세션의 마지막 사용 시각을 과거로 옮겨, 요청이 이 값을 새로 쓰는지 볼 수 있게 합니다. */
    private static <S extends Session> void moveLastAccessedTime(SessionRepository<S> sessions, String id, Instant at) {
        S session = sessions.findById(id);
        session.setLastAccessedTime(at);
        sessions.save(session);
    }

    private String loginCookie() throws Exception {
        var response = login(csrf(null), null, PASSWORD);
        assertEquals(200, response.statusCode());
        return cookie(response);
    }

    private HttpResponse<String> login(CsrfClient csrf, String clientIp, String password) throws Exception {
        var body = mapper.createObjectNode().put("loginEmail", EMAIL).put("password", password);
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Cookie", csrf.cookie()).header(csrf.headerName(), csrf.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (clientIp != null) {
            request.header("Client-Ip", clientIp);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> verifySession(String cookie, String originalMethod, String csrfToken)
            throws Exception {
        var request = HttpRequest.newBuilder(uri("/internal/v1/auth/session"))
                .header("Original-Method", originalMethod)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrfToken != null) {
            request.header("Csrf-Token", csrfToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 쿠키가 없으면 새 세션과 토큰을, 있으면 그 세션의 토큰을 받습니다. */
    private CsrfClient csrf(String cookie) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var data = mapper.readTree(response.body()).get("data");
        return new CsrfClient(cookie != null ? cookie : cookie(response), data.get("headerName").asString(),
                data.get("token").asString());
    }

    private String cookie(HttpResponse<String> response) {
        var cookie = response.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream()).filter(value -> value.getName().equals("SESSION"))
                .findFirst().orElseThrow();
        return cookie.getName() + "=" + cookie.getValue();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String applicationProperty(String filename, String key) {
        try {
            return new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename)).stream()
                    .map(source -> source.getProperty(key))
                    .filter(Objects::nonNull).map(Object::toString).findFirst().orElse(null);
        }
        catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record CsrfClient(String cookie, String headerName, String token) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
            SecurityErrorResponseWriter.class, SessionLoginHandler.class, SessionCurrentUserProvider.class,
            ClockConfiguration.class, JpaAuditingConfiguration.class, SessionRedisConfiguration.class,
            PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
            LoginService.class, RedisLoginAttemptLimiter.class, CurrentMemberService.class, LoginController.class,
            CsrfController.class, InternalSessionController.class,
            LoginControllerIntegrationTest.WebTestConfiguration.class})
    static class HttpTestApplication {
    }
}
