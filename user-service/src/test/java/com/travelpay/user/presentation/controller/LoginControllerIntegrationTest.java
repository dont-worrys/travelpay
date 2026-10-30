package com.travelpay.user.presentation.controller;

import java.io.IOException;
import java.net.HttpCookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.config.CommonAuthWebAutoConfiguration;
import com.travelpay.common.config.CommonWebAutoConfiguration;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.LoginService;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.application.service.MyInfoService;
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
import com.travelpay.user.common.security.session.UserSessionPrincipal;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.infrastructure.redis.RedisLoginAttemptLimiter;
import com.travelpay.user.presentation.dto.CsrfTokenResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(classes = {
        SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, SessionLoginHandler.class, SessionCurrentUserProvider.class,
        ClockConfiguration.class, JpaAuditingConfiguration.class, SessionRedisConfiguration.class,
        PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        LoginService.class, RedisLoginAttemptLimiter.class, CurrentMemberService.class,
        LoginController.class, CsrfController.class,
        LogoutController.class, SessionLogoutHandler.class, MyInfoController.class, MyInfoService.class,
        LoginControllerIntegrationTest.ProbeController.class,
        LoginControllerIntegrationTest.WebTestConfiguration.class
})
@TestPropertySource(properties = {
        "spring.session.timeout=60m",
        "travelpay.user.session.absolute-timeout=24h",
        "travelpay.user.login-attempt.pair-limit=5",
        "travelpay.user.login-attempt.account-limit=20",
        "travelpay.user.login-attempt.window=10m",
        "travelpay.user.login-attempt.block=20m",
        "spring.session.data.redis.namespace=travelpay:user:session:login-test",
        "travelpay.auth.header-context.enabled=false"
})
class LoginControllerIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(24);
    private static final String EMAIL = "login.member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String LOCAL_IP = "127.0.0.1";

    @Autowired private WebApplicationContext context;
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionRepositoryFilter<?> sessionFilter;
    @Autowired private RedisSessionRepository sessions;
    @Autowired private CookieSerializer cookieSerializer;
    @Autowired private GenericContainer<?> sessionRedis;
    @Autowired private StringRedisTemplate redis;

    private MockMvc mvc;
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private UUID userId;

    @BeforeEach
    void setUp() {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        if (context.containsBean("redisSessionErrorResponseFilter")) {
            var registration = (FilterRegistrationBean<?>) context.getBean("redisSessionErrorResponseFilter");
            builder.addFilters(registration.getFilter());
        }
        mvc = builder.addFilters(sessionFilter).apply(springSecurity()).build();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        userId = transactions.execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var user = User.register("홍길동", "010-1500-0001", AT);
            entityManager.persist(user);
            entityManager.persist(UserCredentials.createFor(user, EMAIL, passwordEncoder.encode(PASSWORD), AT));
            return user.getId();
        });
        // 테스트마다 같은 이메일·IP로 실패하므로 이전 테스트의 실패 횟수를 지웁니다.
        redis.delete(redis.keys("travelpay:user:login-attempt:*"));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void logsInWithRedisBackedCsrfAndRestoresTheTypedMemberOnTheNextCookieRequest(AuthRole role) throws Exception {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", role).setParameter("id", userId).executeUpdate());
        var csrf = csrf(null);
        var oldSessionId = sessionId(csrf.cookie());

        var result = login(request(), csrf);

        assertEquals(200, result.getResponse().getStatus());
        assertNoStore(result);
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        assertEquals(3, body.size());
        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("error").isNull());
        assertEquals(2, body.get("data").size());
        assertEquals(userId.toString(), body.get("data").get("userId").asString());
        assertEquals(role.name(), body.get("data").get("role").asString());
        var cookie = sessionCookie(result);
        assertNotEquals(csrf.cookie().getValue(), cookie.getValue());
        assertNull(sessions.findById(oldSessionId));
        Session stored = sessions.findById(sessionId(cookie));
        assertNotNull(stored);
        var securityContext = assertInstanceOf(SecurityContext.class,
                stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        var authentication = securityContext.getAuthentication();
        var principal = assertInstanceOf(UserSessionPrincipal.class, authentication.getPrincipal());
        assertEquals(userId, principal.userId());
        assertEquals(role, principal.role());
        assertEquals(AT, principal.authenticatedAt());
        assertNull(authentication.getCredentials());
        assertEquals(List.of("ROLE_" + role.name()), authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).toList());

        SecurityContextHolder.clearContext();
        var next = mvc.perform(get("/test/login-member").cookie(cookie)
                        .header("User-Id", "11111111-1111-1111-1111-111111111111")
                        .header("User-Role", role == AuthRole.USER ? "MASTER" : "USER"))
                .andExpect(status().isOk()).andReturn();
        var nextData = mapper.readTree(next.getResponse().getContentAsString(UTF_8)).get("data");
        assertEquals(userId.toString(), nextData.get("userId").asString());
        assertEquals(role.name(), nextData.get("role").asString());
        mvc.perform(get("/test/login-member").cookie(csrf.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/test/login-member")).andExpect(status().isUnauthorized());
        transactions.executeWithoutResult(status -> assertEquals(AT,
                entityManager.find(UserCredentials.class, userId).getPasswordChangedAt()));
    }

    @Test
    void replacesTheOldCsrfTokenAndAcceptsTheNewTokenForChangesAfterLogin() throws Exception {
        var before = csrf(null);
        var cookie = sessionCookie(login(request(), before));

        assertError(mvc.perform(post("/test/login-change").cookie(cookie)
                        .header(before.headerName(), before.token())).andReturn(), 403, "COMMON_CSRF_INVALID");
        var after = csrf(cookie);
        mvc.perform(post("/test/login-change").cookie(after.cookie())
                        .header(after.headerName(), after.token())).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {EMAIL, "missing@example.com"})
    void wrongCredentialsNeverAuthenticateTheAnonymousSessionOrRevealWhetherTheEmailExists(String email)
            throws Exception {
        var csrf = csrf(null);
        var body = request().put("loginEmail", email).put("password", "WrongPassword-2026!");

        assertError(login(body, csrf), 401, "USER_INVALID_CREDENTIALS");
        mvc.perform(get("/test/login-member").cookie(csrf.cookie())).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void deniesCorrectCredentialsForMembersWhoseCurrentStateDisallowsLogin(UserStatus state) throws Exception {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());

        assertError(login(request(), csrf(null)), 403,
                state == UserStatus.SUSPENDED ? "USER_ACCOUNT_SUSPENDED" : "USER_ACCOUNT_CLOSED");
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void anExistingSessionCannotUseProtectedApisAfterTheMemberIsBlockedButCanRequestLogout(UserStatus state)
            throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var token = csrf(cookie);
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());

        assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(), 403,
                state == UserStatus.SUSPENDED ? "USER_ACCOUNT_SUSPENDED" : "USER_ACCOUNT_CLOSED");
        mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
                .header(token.headerName(), token.token())).andExpect(status().isNoContent());
        assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(), 401, "COMMON_UNAUTHORIZED");
    }

    @ParameterizedTest
    @MethodSource("changedRoles")
    void currentRoleDrivesBothAuthorizationAndCurrentUserWithoutResettingLoginTime(
            AuthRole initial, AuthRole current, int expectedAdminStatus) throws Exception {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", initial).setParameter("id", userId).executeUpdate());
        var cookie = sessionCookie(login(request(), csrf(null)));
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", current).setParameter("id", userId).executeUpdate());

        mvc.perform(get("/api/v1/admin/probe").cookie(cookie)).andExpect(status().is(expectedAdminStatus));
        var response = mvc.perform(get("/test/login-state").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        var data = mapper.readTree(response.getResponse().getContentAsString(UTF_8)).get("data");
        assertEquals(current.name(), data.get("user").get("role").asString());
        assertEquals(userId.toString(), data.get("user").get("userId").asString());
        assertEquals(AT.toString(), data.get("authenticatedAt").asString());
        assertEquals(List.of("ROLE_" + current.name()), data.get("authorities").valueStream()
                .map(value -> value.asString()).toList());
    }

    @Test
    void missingCurrentMemberCannotBeUsedThroughAnExistingSession() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        transactions.executeWithoutResult(status -> {
            entityManager.createQuery("delete from UserCredentials").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
        });

        assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(), 401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void currentMemberDatabaseFailureIs503AndDoesNotBlockOwnSessionLogoutAuthorization() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var token = csrf(cookie);
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service.users rename to unavailable_users").executeUpdate());
        try {
            var failed = mvc.perform(get("/test/login-member").cookie(cookie)).andReturn();
            assertError(failed, 503, "COMMON_SERVICE_UNAVAILABLE");
            assertDoesNotExpireSessionCookie(failed);
            mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
                    .header(token.headerName(), token.token())).andExpect(status().isNoContent());
        }
        finally {
            transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                    "alter table user_service.unavailable_users rename to users").executeUpdate());
        }
        assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(), 401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void theLogoutAuthorizationExceptionStillRequiresARealMemberSessionAndCsrf() throws Exception {
        var anonymous = csrf(null);
        assertError(mvc.perform(post("/api/v1/auth/logout").cookie(anonymous.cookie())
                        .header(anonymous.headerName(), anonymous.token())).andReturn(), 401, "COMMON_UNAUTHORIZED");
        var cookie = sessionCookie(login(request(), anonymous));
        assertError(mvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andReturn(), 403, "COMMON_CSRF_INVALID");
    }

    @Test
    void logoutDeletesTheRedisSessionExpiresTheCookieAndTheOldCookieNoLongerWorks() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var token = csrf(cookie);
        var key = "travelpay:user:session:login-test:sessions:" + sessionId(cookie);

        var result = mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
                .header(token.headerName(), token.token())).andReturn();

        assertEquals(204, result.getResponse().getStatus());
        assertNoStore(result);
        assertEquals(0, result.getResponse().getContentAsByteArray().length);
        assertTrue(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .anyMatch(value -> value.getName().equals("SESSION") && value.getMaxAge() == 0));
        assertFalse(Boolean.TRUE.equals(sessions.getSessionRedisOperations().hasKey(key)));
        assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(), 401, "COMMON_UNAUTHORIZED");
        assertError(mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
                .header(token.headerName(), token.token())).andReturn(), 401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void aRedisDeleteFailureIs503KeepsTheCookieAndTheSameSessionCanLogOutAfterRecovery() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var token = csrf(cookie);
        var key = "travelpay:user:session:login-test:sessions:" + sessionId(cookie);
        redisDelete(false);
        MvcResult failed;
        try {
            failed = mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
                    .header(token.headerName(), token.token())).andReturn();
        }
        finally {
            redisDelete(true);
        }

        assertError(failed, 503, "COMMON_SERVICE_UNAVAILABLE");
        assertDoesNotExpireSessionCookie(failed);
        assertTrue(Boolean.TRUE.equals(sessions.getSessionRedisOperations().hasKey(key)));
        mvc.perform(get("/test/login-member").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
                .header(token.headerName(), token.token())).andExpect(status().isNoContent());
        assertFalse(Boolean.TRUE.equals(sessions.getSessionRedisOperations().hasKey(key)));
    }

    @Test
    void logoutWithoutASessionIs401WhileOtherChangesWithoutASessionStillNeedCsrf() throws Exception {
        assertError(mvc.perform(post("/api/v1/auth/logout")).andReturn(), 401, "COMMON_UNAUTHORIZED");
        assertError(mvc.perform(post("/test/login-change")).andReturn(), 403, "COMMON_CSRF_INVALID");
    }

    @Test
    void theSessionStillWorksOneSecondBeforeTheAbsoluteTimeout() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        changeLoginTime(sessions, sessionId(cookie), AT.minus(ABSOLUTE_TIMEOUT).plusSeconds(1));

        mvc.perform(get("/test/login-member").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void atTheAbsoluteTimeoutTheSessionIsDeletedAndTheBrowserCanSignInAgain() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var token = csrf(cookie);
        var key = "travelpay:user:session:login-test:sessions:" + sessionId(cookie);
        changeLoginTime(sessions, sessionId(cookie), AT.minus(ABSOLUTE_TIMEOUT));

        assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(), 401, "COMMON_UNAUTHORIZED");
        assertFalse(Boolean.TRUE.equals(sessions.getSessionRedisOperations().hasKey(key)));
        assertError(mvc.perform(post("/api/v1/auth/logout").cookie(cookie)
                .header(token.headerName(), token.token())).andReturn(), 401, "COMMON_UNAUTHORIZED");
        var refreshed = csrf(cookie);
        assertNotEquals(cookie.getValue(), refreshed.cookie().getValue());
        var signedIn = login(request(), refreshed);
        assertEquals(200, signedIn.getResponse().getStatus());
        mvc.perform(get("/test/login-member").cookie(sessionCookie(signedIn))).andExpect(status().isOk());
    }

    @Test
    void aRedisDeleteFailureAtTheAbsoluteTimeoutIs503AndTheSessionIsDeletedAfterRecovery() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var key = "travelpay:user:session:login-test:sessions:" + sessionId(cookie);
        changeLoginTime(sessions, sessionId(cookie), AT.minus(ABSOLUTE_TIMEOUT));
        redisDelete(false);
        MvcResult failed;
        try {
            failed = mvc.perform(get("/test/login-member").cookie(cookie)).andReturn();
        }
        finally {
            redisDelete(true);
        }

        assertError(failed, 503, "COMMON_SERVICE_UNAVAILABLE");
        assertDoesNotExpireSessionCookie(failed);
        assertTrue(Boolean.TRUE.equals(sessions.getSessionRedisOperations().hasKey(key)));
        assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(), 401, "COMMON_UNAUTHORIZED");
        assertFalse(Boolean.TRUE.equals(sessions.getSessionRedisOperations().hasKey(key)));
    }

    @Test
    void myInfoReturnsOnlyTheSignedInMembersOwnFieldsWithoutCaching() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));

        var result = mvc.perform(get("/api/v1/users/me").cookie(cookie)).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertNoStore(result);
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("error").isNull());
        var data = body.get("data");
        assertEquals(Set.of("userId", "realName", "phone", "loginEmail", "status", "role", "loginMethods", "createdAt"),
                data.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        assertEquals(userId.toString(), data.get("userId").asString());
        assertEquals("홍길동", data.get("realName").asString());
        assertEquals("010-1500-0001", data.get("phone").asString());
        assertEquals(EMAIL, data.get("loginEmail").asString());
        assertEquals("ACTIVE", data.get("status").asString());
        assertEquals("USER", data.get("role").asString());
        assertEquals(List.of("PASSWORD"), data.get("loginMethods").valueStream().map(value -> value.asString()).toList());
        var storedCreatedAt = transactions.execute(status -> entityManager.find(User.class, userId).getCreatedAt());
        assertTrue(data.get("createdAt").asString().endsWith("Z"));
        assertEquals(storedCreatedAt, Instant.parse(data.get("createdAt").asString()));
    }

    @Test
    void myInfoNeedsALoginSessionThatHasNotPassedTheAbsoluteTimeout() throws Exception {
        assertError(mvc.perform(get("/api/v1/users/me")).andReturn(), 401, "COMMON_UNAUTHORIZED");

        var cookie = sessionCookie(login(request(), csrf(null)));
        changeLoginTime(sessions, sessionId(cookie), AT.minus(ABSOLUTE_TIMEOUT));

        assertError(mvc.perform(get("/api/v1/users/me").cookie(cookie)).andReturn(), 401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void aDatabaseFailureWhileReadingMyInfoIs503AndKeepsTheSession() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        // 인가 단계는 회원 표만 읽으므로, 로그인 수단 표만 막아 내 정보 조회의 DB 장애를 만듭니다.
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service.user_credentials rename to unavailable_credentials").executeUpdate());
        try {
            var failed = mvc.perform(get("/api/v1/users/me").cookie(cookie)).andReturn();
            assertError(failed, 503, "COMMON_SERVICE_UNAVAILABLE");
            assertDoesNotExpireSessionCookie(failed);
        }
        finally {
            transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                    "alter table user_service.unavailable_credentials rename to user_credentials").executeUpdate());
        }
        mvc.perform(get("/api/v1/users/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void aSuspendedMemberCannotReadMyInfoWithAnExistingSession() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", UserStatus.SUSPENDED).setParameter("id", userId).executeUpdate());

        assertError(mvc.perform(get("/api/v1/users/me").cookie(cookie)).andReturn(), 403, "USER_ACCOUNT_SUSPENDED");
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void validatesLoginJsonBeforeCallingTheApplicationService(String field, String value) throws Exception {
        var result = login(request().put(field, value), csrf(null));

        assertError(result, 400, "COMMON_INVALID_REQUEST");
        var details = mapper.readTree(result.getResponse().getContentAsString(UTF_8)).get("error").get("details");
        assertTrue(details.valueStream().anyMatch(detail -> detail.get("field").asString().equals(field)));
    }

    @Test
    void fiveWrongPasswordsFromOneIpBlockThatIpWith429WhileAnotherIpCanStillSignIn() throws Exception {
        var csrf = csrf(null);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertError(login(wrongPassword(), csrf, "203.0.113.10"), 401, "USER_INVALID_CREDENTIALS");
        }

        assertError(login(request(), csrf, "203.0.113.10"), 429, "COMMON_TOO_MANY_REQUESTS");
        mvc.perform(get("/test/login-member").cookie(csrf.cookie())).andExpect(status().isUnauthorized());
        assertEquals(200, login(request(), csrf, "203.0.113.20").getResponse().getStatus());
    }

    @Test
    void twentyWrongPasswordsSpreadOverIpsBlockTheEmailFromANewIp() throws Exception {
        var csrf = csrf(null);
        for (int host = 1; host <= 20; host++) {
            assertError(login(wrongPassword(), csrf, "198.51.100." + host), 401, "USER_INVALID_CREDENTIALS");
        }

        assertError(login(request(), csrf, "198.51.100.200"), 429, "COMMON_TOO_MANY_REQUESTS");
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCountForThatEmailAndIp() throws Exception {
        var csrf = csrf(null);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertError(login(wrongPassword(), csrf), 401, "USER_INVALID_CREDENTIALS");
        }
        assertEquals(200, login(request(), csrf).getResponse().getStatus());

        var again = csrf(null);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertError(login(wrongPassword(), again), 401, "USER_INVALID_CREDENTIALS");
        }
        assertEquals(200, login(request(), again).getResponse().getStatus());
    }

    @Test
    void invalidInputAndMembersWhoCannotLogInAreNotCountedAsFailedAttempts() throws Exception {
        var csrf = csrf(null);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertError(login(request().put("password", ""), csrf), 400, "COMMON_INVALID_REQUEST");
        }
        updateStatus(UserStatus.SUSPENDED);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertError(login(request(), csrf), 403, "USER_ACCOUNT_SUSPENDED");
        }
        updateStatus(UserStatus.ACTIVE);

        assertEquals(200, login(request(), csrf).getResponse().getStatus());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLoginAttemptStoreFailureIs503AndLeavesALogWithoutTheEmailOrIp(CapturedOutput output) throws Exception {
        var csrf = csrf(null);
        // 세션 저장은 그대로 두고 시도 횟수 스크립트만 막아, 횟수 저장소만 실패하는 상황을 만듭니다.
        redisScripts(false);
        try {
            assertError(login(request(), csrf, "203.0.113.10"), 503, "COMMON_SERVICE_UNAVAILABLE");
        }
        finally {
            redisScripts(true);
        }

        var errors = output.getAll().lines().filter(line -> line.contains("로그인 시도 횟수 저장소 오류")).toList();
        assertEquals(1, errors.size(), errors.toString());
        assertFalse(errors.getFirst().contains(EMAIL));
        assertFalse(errors.getFirst().contains("203.0.113.10"));
        assertEquals(200, login(request(), csrf).getResponse().getStatus());
    }

    @Test
    void loginStillRequiresCsrfEvenThoughItAllowsNonMembers() throws Exception {
        var csrf = csrf(null);

        assertError(mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request())))
                .andReturn(), 403, "COMMON_CSRF_INVALID");
    }

    @Test
    void aTokenFromAnotherSessionCannotAuthorizeLogin() throws Exception {
        var first = csrf(null);
        var second = csrf(null);

        assertError(login(request(), new CsrfClient(first.cookie(), second.headerName(), second.token())),
                403, "COMMON_CSRF_INVALID");
    }

    @Test
    void malformedJsonUsesTheCommonErrorEnvelope() throws Exception {
        var csrf = csrf(null);

        assertError(mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEmail\":"))
                .andReturn(), 400, "COMMON_INVALID_REQUEST");
    }

    @Test
    void theRequestBodyCannotChooseTheMemberIdOrRole() throws Exception {
        var result = login(request().put("role", "MASTER")
                .put("userId", "11111111-1111-1111-1111-111111111111"), csrf(null));

        assertEquals(200, result.getResponse().getStatus());
        var data = mapper.readTree(result.getResponse().getContentAsString(UTF_8)).get("data");
        assertEquals(userId.toString(), data.get("userId").asString());
        assertEquals("USER", data.get("role").asString());
    }

    @Test
    void malformedStoredPasswordHashIsNotReportedAsWrongCredentials() throws Exception {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update UserCredentials c set c.passwordHash = :hash where c.userId = :id")
                .setParameter("hash", "{bcrypt}broken").setParameter("id", userId).executeUpdate());

        assertError(login(request(), csrf(null)), 503, "COMMON_SERVICE_UNAVAILABLE");
    }

    @ParameterizedTest
    @MethodSource("damagedSessionRequests")
    @ExtendWith(OutputCaptureExtension.class)
    void unreadableSessionIsRemovedAndTheBrowserCanFetchCsrfAndSignInAgain(String damage, String firstRequest,
            String warning, CapturedOutput output) throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var key = "travelpay:user:session:login-test:sessions:" + sessionId(cookie);
        var operations = sessions.getSessionRedisOperations();
        assertTrue(Boolean.TRUE.equals(operations.hasKey(key)));
        if (damage.equals("missing")) {
            operations.opsForHash().delete(key, "creationTime");
        }
        else if (damage.equals("wrong-type")) {
            operations.opsForHash().put(key, "creationTime", "wrong-typed-time");
        }
        else {
            try (var connection = context.getBean(RedisConnectionFactory.class).getConnection()) {
                connection.hashCommands().hSet(key.getBytes(UTF_8),
                        ("sessionAttr:" + HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY).getBytes(UTF_8),
                        new byte[] {1, 2, 3});
            }
        }

        if (firstRequest.equals("member")) {
            assertError(mvc.perform(get("/test/login-member").cookie(cookie)).andReturn(),
                    401, "COMMON_UNAUTHORIZED");
        }
        else {
            mvc.perform(get("/api/v1/auth/csrf").cookie(cookie)).andExpect(status().isOk());
        }

        assertFalse(Boolean.TRUE.equals(operations.hasKey(key)));
        var refreshed = csrf(cookie);
        assertNotEquals(cookie.getValue(), refreshed.cookie().getValue());
        var signedIn = login(request(), refreshed);
        assertEquals(200, signedIn.getResponse().getStatus());
        mvc.perform(get("/test/login-member").cookie(sessionCookie(signedIn))).andExpect(status().isOk());
        // 정리된 세션마다 경고는 한 번이며, 로그인 수단인 세션 ID는 남기지 않습니다.
        var warnings = output.getAll().lines().filter(line -> line.endsWith(warning)).toList();
        assertEquals(1, warnings.size(), warnings.toString());
        assertFalse(warnings.getFirst().contains(sessionId(cookie)));
        assertFalse(warnings.getFirst().contains(cookie.getValue()));
    }

    @Test
    void invalidSessionCannotReuseTheOldCsrfButCanRefreshBeforeSigningInAgain() throws Exception {
        var cookie = sessionCookie(login(request(), csrf(null)));
        var token = csrf(cookie);
        var key = "travelpay:user:session:login-test:sessions:" + sessionId(cookie);
        sessions.getSessionRedisOperations().opsForHash().delete(key, "creationTime");

        var denied = login(request(), token);

        assertError(denied, 403, "COMMON_CSRF_INVALID");
        assertEquals(200, login(request(), csrf(sessionCookie(denied))).getResponse().getStatus());
    }

    @Test
    void aDatabaseReadFailureReturns503AndTheCookieCanBeUsedAfterRecovery() throws Exception {
        var csrf = csrf(null);
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service.user_credentials rename to unavailable_credentials").executeUpdate());
        try {
            var failed = login(request(), csrf);
            assertError(failed, 503, "COMMON_SERVICE_UNAVAILABLE");
            assertDoesNotExpireSessionCookie(failed);
        }
        finally {
            transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                    "alter table user_service.unavailable_credentials rename to user_credentials").executeUpdate());
        }
        assertEquals(200, login(request(), csrf).getResponse().getStatus());
    }

    @Test
    void theSessionFailureHandlerIsRegisteredBeforeTheSpringSessionFilter() {
        var registration = (FilterRegistrationBean<?>) context.getBean("redisSessionErrorResponseFilter");

        assertTrue(registration.getOrder() < SessionRepositoryFilter.DEFAULT_ORDER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "member"})
    void aRealRedisReadTimeoutReturns503WithoutExpiringTheCookieAndRecovers(String target) throws Exception {
        var csrf = csrf(null);
        var cookie = target.equals("member") ? sessionCookie(login(request(), csrf)) : csrf.cookie();
        sessionRedis.getDockerClient().pauseContainerCmd(sessionRedis.getContainerId()).exec();
        try {
            var failed = target.equals("login") ? login(request(), csrf)
                    : mvc.perform(get("/test/login-member").cookie(cookie)).andReturn();
            assertError(failed, 503, "COMMON_SERVICE_UNAVAILABLE");
            assertDoesNotExpireSessionCookie(failed);
        }
        finally {
            sessionRedis.getDockerClient().unpauseContainerCmd(sessionRedis.getContainerId()).exec();
        }
        if (target.equals("login")) {
            assertEquals(200, login(request(), csrf).getResponse().getStatus());
        }
        else {
            mvc.perform(get("/test/login-member").cookie(cookie)).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "csrf"})
    void aRealRedisMemoryWriteFailureIsNotReportedAsSuccess(String target) throws Exception {
        var csrf = csrf(null);
        configureRedis("maxmemory-policy", "noeviction");
        configureRedis("maxmemory", "1");
        try {
            var failed = target.equals("login") ? login(request(), csrf)
                    : mvc.perform(get("/api/v1/auth/csrf")).andReturn();
            assertError(failed, 503, "COMMON_SERVICE_UNAVAILABLE");
            assertDoesNotExpireSessionCookie(failed);
        }
        finally {
            configureRedis("maxmemory", "0");
        }
        assertEquals(200, login(request(), csrf(null)).getResponse().getStatus());
    }

    private void configureRedis(String key, String value) throws IOException, InterruptedException {
        var result = sessionRedis.execInContainer("redis-cli", "-e", "CONFIG", "SET", key, value);
        assertEquals(0, result.getExitCode());
    }

    private void redisScripts(boolean allowed) throws IOException, InterruptedException {
        var result = sessionRedis.execInContainer("redis-cli", "-e", "ACL", "SETUSER", "default",
                allowed ? "+eval" : "-eval", allowed ? "+evalsha" : "-evalsha");
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    private void redisDelete(boolean allowed) throws IOException, InterruptedException {
        var result = sessionRedis.execInContainer("redis-cli", "-e", "ACL", "SETUSER", "default", allowed ? "+del" : "-del");
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    /** 저장된 로그인 시각만 바꿔 시간이 흐른 상황을 만듭니다. 판단에 쓰는 현재 시각은 고정 시계입니다. */
    private <S extends Session> void changeLoginTime(SessionRepository<S> repository, String sessionId, Instant loginTime) {
        S session = repository.findById(sessionId);
        SecurityContext stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var principal = (UserSessionPrincipal) stored.getAuthentication().getPrincipal();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserSessionPrincipal(principal.userId(), principal.role(), loginTime), null,
                stored.getAuthentication().getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
    }

    private CsrfClient csrf(Cookie existing) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (existing != null) { request.cookie(existing); }
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var token = mapper.treeToValue(mapper.readTree(result.getResponse().getContentAsString(UTF_8)).get("data"),
                CsrfTokenResponse.class);
        var cookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty() ? existing : sessionCookie(result);
        return new CsrfClient(cookie, token.headerName(), token.token());
    }

    private Cookie sessionCookie(MvcResult result) {
        var cookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow();
        return new Cookie(cookie.getName(), cookie.getValue());
    }

    private String sessionId(Cookie cookie) {
        var request = new MockHttpServletRequest();
        request.setCookies(cookie);
        return cookieSerializer.readCookieValues(request).getFirst();
    }

    private MvcResult login(ObjectNode body, CsrfClient csrf) throws Exception {
        return login(body, csrf, LOCAL_IP);
    }

    private MvcResult login(ObjectNode body, CsrfClient csrf, String clientIp) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(body))
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        }))
                .andReturn();
    }

    private ObjectNode request() {
        return mapper.createObjectNode().put("loginEmail", "  Login.Member@Example.COM  ").put("password", PASSWORD);
    }

    private ObjectNode wrongPassword() {
        return request().put("password", "WrongPassword-2026!");
    }

    private void updateStatus(UserStatus state) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());
    }

    private void assertError(MvcResult result, int status, String code) throws IOException {
        assertEquals(status, result.getResponse().getStatus());
        var text = result.getResponse().getContentAsString(UTF_8);
        var body = mapper.readTree(text);
        assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("data").isNull());
        assertEquals(code, body.get("error").get("code").asString());
        assertTrue(body.get("error").get("details").isArray());
        assertFalse(text.contains(PASSWORD));
        assertFalse(text.contains(EMAIL));
    }

    private void assertNoStore(MvcResult result) {
        var cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl, "인증·개인정보 응답에는 캐시 금지 헤더가 있어야 합니다.");
        assertTrue(cacheControl.contains("no-store"), cacheControl);
    }

    private void assertDoesNotExpireSessionCookie(MvcResult result) {
        assertTrue(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .noneMatch(cookie -> cookie.getName().equals("SESSION") && cookie.getMaxAge() == 0));
    }

    private static Stream<Arguments> invalidInputs() {
        return Stream.of(Arguments.of("loginEmail", "not-an-email"), Arguments.of("loginEmail", (String) null),
                Arguments.of("password", ""), Arguments.of("password", "a".repeat(73)));
    }

    private static Stream<Arguments> damagedSessionRequests() {
        var warnings = Map.of(
                "missing", "형식이 맞지 않는 세션을 없는 세션으로 처리합니다: IllegalStateException",
                "wrong-type", "형식이 맞지 않는 세션을 없는 세션으로 처리합니다: ClassCastException",
                "serialized-auth", "세션 값을 읽지 못했습니다: EOFException");
        return Stream.of("missing", "wrong-type", "serialized-auth")
                .flatMap(damage -> Stream.of("member", "csrf")
                        .map(target -> Arguments.of(damage, target, warnings.get(damage))));
    }

    private static Stream<Arguments> changedRoles() {
        return Stream.of(Arguments.of(AuthRole.MASTER, AuthRole.USER, 403),
                Arguments.of(AuthRole.USER, AuthRole.MASTER, 200));
    }

    private record CsrfClient(Cookie cookie, String headerName, String token) {
    }

    /** 실제 User 본인 API를 먼저 만들지 않고 세션·Provider 연결만 확인합니다. */
    @TestComponent
    @RestController
    static class ProbeController {
        @GetMapping("/test/login-member")
        ApiResponse<AuthenticatedUser> member(@CurrentUser AuthenticatedUser user) { return ApiResponse.success(user); }

        @PostMapping("/test/login-change")
        ApiResponse<Void> change() { return ApiResponse.success(null); }

        @GetMapping("/api/v1/admin/probe")
        ApiResponse<Void> admin() { return ApiResponse.success(null); }

        @GetMapping("/test/login-state")
        ApiResponse<IdentityState> state(@CurrentUser AuthenticatedUser user, Authentication authentication) {
            var principal = (UserSessionPrincipal) authentication.getPrincipal();
            return ApiResponse.success(new IdentityState(user, principal.authenticatedAt(),
                    authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).toList()));
        }
    }

    private record IdentityState(AuthenticatedUser user, Instant authenticatedAt, List<String> authorities) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class,
            DataRedisAutoConfiguration.class, SessionDataRedisAutoConfiguration.class, SessionAutoConfiguration.class,
            CommonWebAutoConfiguration.class, CommonAuthWebAutoConfiguration.class})
    static class WebTestConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(AT, ZoneOffset.UTC); }
        @Bean RedisPassword sessionPassword() { return RedisPassword.of(UUID.randomUUID().toString()); }

        @Bean(destroyMethod = "stop")
        GenericContainer<?> sessionRedis(RedisPassword sessionPassword) {
            var password = new String(sessionPassword.get());
            var redis = new GenericContainer<>(DockerImageName.parse("redis:8.2.11-bookworm"))
                    .withExposedPorts(6379).withEnv("REDISCLI_AUTH", password)
                    .withCommand("redis-server", "--requirepass", password, "--appendonly", "no", "--save", "");
            redis.start();
            return redis;
        }

        @Bean
        LettuceConnectionFactory sessionConnectionFactory(GenericContainer<?> sessionRedis,
                RedisPassword sessionPassword) {
            var configuration = new RedisStandaloneConfiguration(sessionRedis.getHost(), sessionRedis.getMappedPort(6379));
            configuration.setPassword(sessionPassword);
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1))
                    .shutdownTimeout(Duration.ofMillis(100)).build();
            return new LettuceConnectionFactory(configuration, client);
        }
    }
}
