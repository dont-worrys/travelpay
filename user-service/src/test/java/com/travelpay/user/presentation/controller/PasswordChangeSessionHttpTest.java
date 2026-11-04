package com.travelpay.user.presentation.controller;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.application.service.LoginService;
import com.travelpay.user.application.service.MyInfoService;
import com.travelpay.user.application.service.PasswordChangeService;
import com.travelpay.user.common.config.ClockConfiguration;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.config.SessionRedisConfiguration;
import com.travelpay.user.common.security.SecurityConfiguration;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import com.travelpay.user.common.security.session.SessionCurrentUserProvider;
import com.travelpay.user.common.security.session.SessionLoginHandler;
import com.travelpay.user.common.security.session.SessionLogoutHandler;
import com.travelpay.user.common.security.session.SessionPasswordChangeHandler;
import com.travelpay.user.common.security.session.UserSessionPrincipal;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.infrastructure.redis.RedisLoginAttemptLimiter;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 비밀번호 변경 API의 인증·DB 커밋·Redis 세션 저장 및 오류 응답을 함께 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PasswordChangeSessionHttpTest.HttpApplication.class,
        args = "--spring.config.name=password-change-session-test",
        properties = {
                "spring.application.name=password-session-http-test",
                "spring.jpa.open-in-view=false",
                "spring.session.timeout=60m",
                "travelpay.user.session.absolute-timeout=24h",
                "travelpay.user.password-change.transaction-timeout=2s",
                "travelpay.user.login-attempt.pair-limit=5",
                "travelpay.user.login-attempt.account-limit=20",
                "travelpay.user.login-attempt.window=10m",
                "travelpay.user.login-attempt.block=20m",
                "spring.session.data.redis.namespace=travelpay:user:session:password-handler-test",
                "spring.data.redis.repositories.enabled=false",
                "travelpay.auth.header-context.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.security.user.password=unused-test-framework-password",
                "spring.flyway.enabled=false",
                "logging.level.root=WARN"
        })
class PasswordChangeSessionHttpTest {

    private static final String EMAIL = "session.password@example.com";
    private static final String OLD_PASSWORD = "OriginalPassword-2026!";
    private static final String NEW_PASSWORD = "ChangedPassword-2026!";
    private static final String CHANGE_PATH = "/api/v1/users/me/password";

    @Value("${local.server.port}") private int port;
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder encoder;
    @Autowired private SessionRepository<? extends Session> sessions;
    @Autowired private CookieSerializer cookieSerializer;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisConnectionFactory connections;
    @Autowired private Clock clock;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private UUID userId;

    @BeforeEach
    void setUp() {
        config("maxmemory", "0");
        redis.delete(redis.keys("travelpay:user:login-attempt:*"));
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        userId = new TransactionTemplate(transactionManager).execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var member = User.register("홍길동", "010-1800-0001", clock.instant().minusSeconds(300));
            entityManager.persist(member);
            entityManager.persist(UserCredentials.createFor(member, EMAIL, encoder.encode(OLD_PASSWORD),
                    clock.instant().minusSeconds(300)));
            return member.getId();
        });
    }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void aSuccessfulUpdatePersistsTheNewIdentityAndCsrfWithoutExtendingLoginTime(AuthRole currentRole) throws Exception {
        var signed = signedIn();
        var originalLoginAt = clock.instant().minusSeconds(60);
        rewriteLoginTime(sessions, signed.cookie(), originalLoginAt);
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", currentRole).setParameter("id", userId).executeUpdate());

        var response = change(signed, OLD_PASSWORD, null);

        assertEquals(204, response.statusCode());
        assertTrue(response.body().isEmpty());
        var renewedCookie = responseCookie(response, null);
        assertFalse(signed.cookie().equals(renewedCookie), "현재 세션 ID를 교체해야 합니다.");
        assertNull(sessions.findById(sessionId(signed.cookie())));
        var stored = sessions.findById(sessionId(renewedCookie));
        SecurityContext context = stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var principal = (UserSessionPrincipal) context.getAuthentication().getPrincipal();
        assertEquals(userId, principal.userId());
        assertEquals(currentRole, principal.role());
        assertEquals(originalLoginAt, principal.authenticatedAt());
        assertTrue(encoder.matches(NEW_PASSWORD, storedPassword()));
        assertEquals(401, member(signed.cookie()).statusCode());
        var member = member(renewedCookie);
        assertEquals(200, member.statusCode());
        assertEquals(currentRole.name(), mapper.readTree(member.body()).get("data").get("role").asString());
        assertError(change(new CsrfClient(renewedCookie, signed.header(), signed.token()), NEW_PASSWORD, null),
                403, "COMMON_CSRF_INVALID");
        var fresh = csrf(renewedCookie);
        var logout = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("Cookie", fresh.cookie()).header(fresh.header(), fresh.token())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, logout.statusCode());
        assertTrue(logout.body().isEmpty());
        var anonymous = csrf(null);
        assertError(login(OLD_PASSWORD, anonymous), 401, "USER_INVALID_CREDENTIALS");
        assertEquals(200, login(NEW_PASSWORD, anonymous).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"oom", "invalidated"})
    void aLateSessionFailureReportsTheCommittedPasswordAndCanRecoverBySigningInAgain(String mode) throws Exception {
        var signed = signedIn();
        HttpResponse<String> response;
        try {
            response = change(signed, OLD_PASSWORD, mode);
            assertError(response, 503, "USER_ACCOUNT_UPDATED_SESSION_UNAVAILABLE");
            assertTrue(encoder.matches(NEW_PASSWORD, storedPassword()));
            assertFalse(encoder.matches(OLD_PASSWORD, storedPassword()));
            assertTrue(response.headers().allValues("Set-Cookie").stream()
                    .flatMap(value -> HttpCookie.parse(value).stream()).noneMatch(cookie -> cookie.getMaxAge() == 0));
        } finally {
            config("maxmemory", "0");
        }
        var recovered = csrf(responseCookie(response, signed.cookie()));
        assertError(login(OLD_PASSWORD, recovered), 401, "USER_INVALID_CREDENTIALS");
        assertEquals(200, login(NEW_PASSWORD, recovered).statusCode());
    }

    @Test
    void aFailureBeforeDatabaseCommitKeepsThePasswordAndExistingSession() throws Exception {
        var signed = signedIn();

        var response = change(signed, "WrongPassword-2026!", "oom");

        assertError(response, 400, "USER_CURRENT_PASSWORD_MISMATCH");
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
        assertEquals(200, member(signed.cookie()).statusCode());
    }

    @Test
    void anAnonymousSessionWithValidCsrfCannotChangeAPassword() throws Exception {
        var anonymous = csrf(null);

        assertError(change(anonymous, OLD_PASSWORD, null), 401, "COMMON_UNAUTHORIZED");
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-csrf-token"})
    void aMissingOrInvalidCsrfTokenCannotChangeThePassword(String token) throws Exception {
        var signed = signedIn();
        var request = HttpRequest.newBuilder(uri(CHANGE_PATH))
                .header("Cookie", signed.cookie()).header("Content-Type", "application/json");
        if (!token.isEmpty()) request.header(signed.header(), token);
        var response = client.send(request.method("PATCH", HttpRequest.BodyPublishers.ofString(
                passwordBody(OLD_PASSWORD, NEW_PASSWORD))).build(), HttpResponse.BodyHandlers.ofString());

        assertError(response, 403, "COMMON_CSRF_INVALID");
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
        assertEquals(200, member(signed.cookie()).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "Invalid Password-2026!"})
    void anInvalidNewPasswordReturnsItsFieldErrorWithoutChangingTheSession(String password) throws Exception {
        var signed = signedIn();
        var response = patch(signed, passwordBody(OLD_PASSWORD, password), null);

        assertError(response, 400, "COMMON_INVALID_REQUEST");
        assertTrue(mapper.readTree(response.body()).get("error").get("details").valueStream()
                .anyMatch(detail -> "newPassword".equals(detail.get("field").asString())));
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
        assertEquals(200, member(signed.cookie()).statusCode());
    }

    @Test
    void anInvalidCurrentPasswordIsRejectedByRequestValidation() throws Exception {
        var signed = signedIn();
        var response = patch(signed, passwordBody("", NEW_PASSWORD), null);

        assertError(response, 400, "COMMON_INVALID_REQUEST");
        assertTrue(mapper.readTree(response.body()).get("error").get("details").valueStream()
                .anyMatch(detail -> "currentPassword".equals(detail.get("field").asString())));
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
    }

    @Test
    void theSamePasswordCannotBeReused() throws Exception {
        var signed = signedIn();

        assertError(patch(signed, passwordBody(OLD_PASSWORD, OLD_PASSWORD), null),
                400, "USER_NEW_PASSWORD_SAME_AS_CURRENT");
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
        assertEquals(200, member(signed.cookie()).statusCode());
    }

    @Test
    void forgedMemberHeadersCannotSelectAnotherAccount() throws Exception {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        var otherId = new TransactionTemplate(transactionManager).execute(status -> {
            var other = User.register("다른회원", "010-1800-0002", clock.instant());
            entityManager.persist(other);
            entityManager.persist(UserCredentials.createFor(other, "other.password@example.com",
                    encoder.encode(OLD_PASSWORD), clock.instant()));
            return other.getId();
        });
        var signed = signedIn();
        var response = client.send(HttpRequest.newBuilder(uri(CHANGE_PATH))
                .header("Cookie", signed.cookie()).header(signed.header(), signed.token())
                .header("User-Id", otherId.toString()).header("User-Role", "MASTER")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(passwordBody(OLD_PASSWORD, NEW_PASSWORD)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode());
        assertTrue(encoder.matches(NEW_PASSWORD, storedPassword()));
        var otherHash = new TransactionTemplate(transactionManager).execute(status ->
                entityManager.find(UserCredentials.class, otherId).getPasswordHash());
        assertTrue(encoder.matches(OLD_PASSWORD, otherHash));
        var identity = mapper.readTree(member(responseCookie(response, signed.cookie())).body()).get("data");
        assertEquals(userId.toString(), identity.get("userId").asString());
        assertEquals("USER", identity.get("role").asString());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void aBlockedMemberCannotChangeThePassword(UserStatus memberStatus) throws Exception {
        var signed = signedIn();
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :status where u.id = :id")
                .setParameter("status", memberStatus).setParameter("id", userId).executeUpdate());

        assertError(change(signed, OLD_PASSWORD, null), 403, "USER_ACCOUNT_" + memberStatus.name());
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
    }

    @Test
    void loginAndCurrentPasswordFailuresShareTheSameRateLimit() throws Exception {
        var signed = signedIn();
        for (int attempt = 0; attempt < 4; attempt++) {
            assertError(login("WrongPassword-2026!", signed), 401, "USER_INVALID_CREDENTIALS");
        }
        assertError(change(signed, "WrongPassword-2026!", null), 400, "USER_CURRENT_PASSWORD_MISMATCH");

        assertError(change(signed, OLD_PASSWORD, null), 429, "COMMON_TOO_MANY_REQUESTS");
        assertError(login(OLD_PASSWORD, signed), 429, "COMMON_TOO_MANY_REQUESTS");
        assertTrue(encoder.matches(OLD_PASSWORD, storedPassword()));
        assertEquals(200, member(signed.cookie()).statusCode());
    }

    @Test
    void anotherDeviceRemainsSignedInWhenTheCurrentSessionIsRenewed() throws Exception {
        var currentDevice = signedIn();
        var otherDevice = signedIn();

        assertEquals(204, change(currentDevice, OLD_PASSWORD, null).statusCode());

        assertEquals(401, member(currentDevice.cookie()).statusCode());
        assertEquals(200, member(otherDevice.cookie()).statusCode());
        assertTrue(encoder.matches(NEW_PASSWORD, storedPassword()));
    }

    private CsrfClient signedIn() throws Exception {
        var anonymous = csrf(null);
        var response = login(OLD_PASSWORD, anonymous);
        assertEquals(200, response.statusCode());
        return csrf(responseCookie(response, null));
    }

    private CsrfClient csrf(String cookie) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).timeout(Duration.ofSeconds(10));
        if (cookie != null) request.header("Cookie", cookie);
        var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var data = mapper.readTree(response.body()).get("data");
        return new CsrfClient(responseCookie(response, cookie), data.get("headerName").asString(), data.get("token").asString());
    }

    private HttpResponse<String> login(String password, CsrfClient csrf) throws Exception {
        var body = mapper.createObjectNode().put("loginEmail", EMAIL).put("password", password);
        return client.send(HttpRequest.newBuilder(uri("/api/v1/auth/login")).timeout(Duration.ofSeconds(10))
                .header("Cookie", csrf.cookie()).header(csrf.header(), csrf.token()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> change(CsrfClient csrf, String current, String failure) throws Exception {
        return patch(csrf, passwordBody(current, NEW_PASSWORD), failure);
    }

    private String passwordBody(String current, String password) {
        return mapper.writeValueAsString(mapper.createObjectNode()
                .put("currentPassword", current).put("newPassword", password));
    }

    private HttpResponse<String> patch(CsrfClient csrf, String body, String failure) throws Exception {
        var request = HttpRequest.newBuilder(uri(CHANGE_PATH)).timeout(Duration.ofSeconds(10))
                .header("Cookie", csrf.cookie()).header(csrf.header(), csrf.token()).header("Content-Type", "application/json");
        if (failure != null) request.header("Test-Session-Failure", failure);
        return client.send(request.method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> member(String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/users/me")).timeout(Duration.ofSeconds(10))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String responseCookie(HttpResponse<?> response, String fallback) {
        return response.headers().allValues("Set-Cookie").stream().flatMap(value -> HttpCookie.parse(value).stream())
                .filter(cookie -> cookie.getName().equals("SESSION"))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue()).findFirst().orElse(fallback);
    }

    private String sessionId(String cookie) {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("SESSION", cookie.substring(cookie.indexOf('=') + 1)));
        return cookieSerializer.readCookieValues(request).getFirst();
    }

    private <S extends Session> void rewriteLoginTime(SessionRepository<S> repository, String cookie, Instant authenticatedAt) {
        S stored = repository.findById(sessionId(cookie));
        SecurityContext context = stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var authentication = context.getAuthentication();
        var principal = (UserSessionPrincipal) authentication.getPrincipal();
        var updated = SecurityContextHolder.createEmptyContext();
        updated.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserSessionPrincipal(principal.userId(), principal.role(), authenticatedAt), null, authentication.getAuthorities()));
        stored.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, updated);
        repository.save(stored);
    }

    private String storedPassword() {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.find(UserCredentials.class, userId).getPasswordHash());
    }

    private void config(String name, String value) {
        try (var connection = connections.getConnection()) { connection.serverCommands().setConfig(name, value); }
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        var body = mapper.readTree(response.body());
        assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("data").isNull());
        assertEquals(code, body.get("error").get("code").asString());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertFalse(response.body().contains(OLD_PASSWORD));
        assertFalse(response.body().contains(NEW_PASSWORD));
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private record CsrfClient(String cookie, String header, String token) { }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
            SecurityErrorResponseWriter.class, SessionLoginHandler.class, SessionPasswordChangeHandler.class,
            SessionCurrentUserProvider.class, ClockConfiguration.class, JpaAuditingConfiguration.class, SessionRedisConfiguration.class,
            PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class, LoginService.class, PasswordChangeService.class,
            RedisLoginAttemptLimiter.class, CurrentMemberService.class, MyInfoService.class,
            LoginController.class, CsrfController.class, PasswordChangeController.class,
            MyInfoController.class, LogoutController.class, SessionLogoutHandler.class,
            LoginControllerIntegrationTest.WebTestConfiguration.class})
    static class HttpApplication {

        /** Controller 반환 후, Spring Session이 Redis에 저장하기 직전에 격리된 테스트 저장소만 변경합니다. */
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> lateSessionFailure(
                RedisConnectionFactory connections, SessionRepository<? extends Session> sessions) {
            var filter = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    var current = request.getSession(false);
                    var originalId = current == null ? null : current.getId();
                    chain.doFilter(request, response);
                    if (!CHANGE_PATH.equals(request.getRequestURI())
                            || !Boolean.TRUE.equals(request.getAttribute(
                                    SessionPasswordChangeHandler.ACCOUNT_UPDATED_ATTRIBUTE))) {
                        return;
                    }
                    var mode = request.getHeader("Test-Session-Failure");
                    if ("oom".equals(mode)) {
                        try (var connection = connections.getConnection()) {
                            connection.serverCommands().setConfig("maxmemory-policy", "noeviction");
                            connection.serverCommands().setConfig("maxmemory", "1");
                        }
                    } else if ("invalidated".equals(mode) && originalId != null) {
                        sessions.deleteById(originalId);
                    }
                }
            };
            var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
            registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
            registration.addUrlPatterns(CHANGE_PATH);
            return registration;
        }
    }
}
