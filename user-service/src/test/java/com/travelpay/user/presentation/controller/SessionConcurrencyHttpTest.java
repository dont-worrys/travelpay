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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.LoginService;
import com.travelpay.user.application.service.CurrentMemberService;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SessionConcurrencyHttpTest.HttpTestApplication.class,
        args = "--spring.config.name=session-concurrency-test",
        properties = {
                "spring.application.name=user-session-http-test",
                "spring.jpa.open-in-view=false",
                "spring.session.timeout=60m",
                "travelpay.user.session.absolute-timeout=24h",
                "travelpay.user.login-attempt.pair-limit=5",
                "travelpay.user.login-attempt.account-limit=20",
                "travelpay.user.login-attempt.window=10m",
                "travelpay.user.login-attempt.block=20m",
                "spring.session.data.redis.namespace=travelpay:user:session:concurrency-test",
                "spring.data.redis.repositories.enabled=false",
                "travelpay.auth.header-context.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.security.user.password=unused-test-framework-password",
                "logging.level.root=WARN"
        })
class SessionConcurrencyHttpTest {

    private static final String EMAIL = "concurrent.member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final Instant AT = Instant.parse("2026-10-30T00:00:00Z");

    @Value("${local.server.port}") private int port;
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OverlapGate gate;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private UUID userId;

    @BeforeEach
    void setUp() {
        gate.reset();
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        userId = new TransactionTemplate(transactionManager).execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var user = User.register("홍길동", "010-1500-0001", AT);
            entityManager.persist(user);
            entityManager.persist(UserCredentials.createFor(user, EMAIL, passwordEncoder.encode(PASSWORD), AT));
            return user.getId();
        });
    }

    @Test
    void aPublicRequestThatOutlivesLoginReturnsConflictWithoutDestroyingTheNewSession() throws Exception {
        var csrf = csrf();
        var slow = client.sendAsync(HttpRequest.newBuilder(uri("/api/v1/terms"))
                .timeout(Duration.ofSeconds(20)).header("Cookie", csrf.cookie()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(gate.awaitEntered());
        HttpResponse<String> signedIn;
        try {
            signedIn = client.send(loginRequest(csrf, false), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signedIn.statusCode());
        }
        finally {
            gate.release();
        }

        assertConflict(slow.get(15, TimeUnit.SECONDS));
        assertWinnerSessionWorks(signedIn);
    }

    @Test
    void aSecondLoginHoldingTheOldSessionReturnsConflictAfterTheFirstLoginFinishes() throws Exception {
        var csrf = csrf();
        var delayed = client.sendAsync(loginRequest(csrf, true), HttpResponse.BodyHandlers.ofString());
        assertTrue(gate.awaitEntered());
        HttpResponse<String> signedIn;
        try {
            signedIn = client.send(loginRequest(csrf, false), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signedIn.statusCode());
        }
        finally {
            gate.release();
        }

        assertConflict(delayed.get(15, TimeUnit.SECONDS));
        assertWinnerSessionWorks(signedIn);
    }

    private CsrfClient csrf() throws Exception {
        var result = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        var data = mapper.readTree(result.body()).get("data");
        return new CsrfClient(cookie(result), data.get("headerName").asString(), data.get("token").asString());
    }

    private HttpRequest loginRequest(CsrfClient csrf, boolean hold) {
        var body = mapper.createObjectNode().put("loginEmail", EMAIL).put("password", PASSWORD);
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/login")).timeout(Duration.ofSeconds(20))
                .header("Cookie", csrf.cookie()).header(csrf.headerName(), csrf.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (hold) { request.header("Test-Hold-Session", "true"); }
        return request.build();
    }

    private void assertConflict(HttpResponse<String> response) {
        assertEquals(409, response.statusCode());
        var body = mapper.readTree(response.body());
        assertFalse(body.get("success").asBoolean());
        assertEquals("COMMON_CONFLICT", body.get("error").get("code").asString());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream()).noneMatch(value -> value.getMaxAge() == 0));
    }

    private void assertWinnerSessionWorks(HttpResponse<String> signedIn) throws Exception {
        var member = client.send(HttpRequest.newBuilder(uri("/test/member")).header("Cookie", cookie(signedIn))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, member.statusCode());
        assertEquals(userId.toString(), mapper.readTree(member.body()).get("data").get("userId").asString());
    }

    private String cookie(HttpResponse<String> response) {
        var cookie = response.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream()).filter(value -> value.getName().equals("SESSION"))
                .findFirst().orElseThrow();
        return cookie.getName() + "=" + cookie.getValue();
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private record CsrfClient(String cookie, String headerName, String token) {
    }

    static final class OverlapGate {
        private volatile Gate current = new Gate(new CountDownLatch(1), new CountDownLatch(1));
        void reset() { current = new Gate(new CountDownLatch(1), new CountDownLatch(1)); }
        boolean awaitEntered() throws InterruptedException { return current.entered().await(10, TimeUnit.SECONDS); }
        void release() { current.release().countDown(); }
        void hold() throws InterruptedException {
            var scenario = current;
            scenario.entered().countDown();
            if (!scenario.release().await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 요청을 해제하지 못했습니다.");
            }
        }
        private record Gate(CountDownLatch entered, CountDownLatch release) { }
    }

    @RestController
    static class ProbeController {
        private final OverlapGate gate;
        ProbeController(OverlapGate gate) { this.gate = gate; }

        @GetMapping("/api/v1/terms")
        ApiResponse<Void> slowPublic(HttpServletRequest request) throws InterruptedException {
            assertNotNull(request.getSession(false));
            gate.hold();
            return ApiResponse.success(null);
        }

        @GetMapping("/test/member")
        ApiResponse<AuthenticatedUser> member(@CurrentUser AuthenticatedUser user) { return ApiResponse.success(user); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
            SecurityErrorResponseWriter.class, SessionLoginHandler.class, SessionCurrentUserProvider.class,
            ClockConfiguration.class, JpaAuditingConfiguration.class, SessionRedisConfiguration.class,
            PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
            LoginService.class, RedisLoginAttemptLimiter.class, CurrentMemberService.class, LoginController.class,
            CsrfController.class, ProbeController.class,
            LoginControllerIntegrationTest.WebTestConfiguration.class})
    static class HttpTestApplication {
        @Bean OverlapGate overlapGate() { return new OverlapGate(); }

        @Bean
        WebMvcConfigurer overlapConfigurer(OverlapGate gate) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                                throws Exception {
                            if ("true".equals(request.getHeader("Test-Hold-Session"))) {
                                assertNotNull(request.getSession(false));
                                gate.hold();
                            }
                            return true;
                        }
                    });
                }
            };
        }
    }
}
