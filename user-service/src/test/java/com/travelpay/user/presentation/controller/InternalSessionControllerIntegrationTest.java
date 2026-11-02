package com.travelpay.user.presentation.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.config.CommonAuthWebAutoConfiguration;
import com.travelpay.common.config.CommonWebAutoConfiguration;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.security.SecurityConfiguration;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import com.travelpay.user.common.security.session.SessionCurrentUserProvider;
import com.travelpay.user.common.security.session.UserSessionPrincipal;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.presentation.dto.CsrfTokenResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringJUnitWebConfig(classes = {
        SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        CurrentMemberService.class, SessionCurrentUserProvider.class,
        CsrfController.class, InternalSessionController.class,
        InternalSessionControllerIntegrationTest.WebTestConfiguration.class
})
class InternalSessionControllerIntegrationTest {

    private static final String PATH = "/internal/v1/auth/session";
    private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired private WebApplicationContext context;
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mvc;
    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anActiveMembersSessionReturnsTheMemberIdAndRoleWithoutTouchingTheCookie() throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.USER);

        var result = mvc.perform(verify(sessionOf(userId, AuthRole.USER), "GET")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(mapper.readTree("""
                {"success": true, "data": {"userId": "%s", "role": "USER"}, "error": null}
                """.formatted(userId)), mapper.readTree(result.getResponse().getContentAsString(UTF_8)));
        assertNull(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        var cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("no-store"), cacheControl);
    }

    @Test
    void theRoleComesFromTheDatabaseNotFromTheRoleSavedAtLogin() throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.MASTER);

        var result = mvc.perform(verify(sessionOf(userId, AuthRole.USER), "GET")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("MASTER", mapper.readTree(result.getResponse().getContentAsString(UTF_8))
                .get("data").get("role").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void withoutASessionItIs401AndNoSessionIsCreated(String originalMethod) throws Exception {
        var result = mvc.perform(post(PATH).header("Original-Method", originalMethod)).andReturn();

        assertError(result, 401, "COMMON_UNAUTHORIZED");
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void aSessionCreatedBeforeLoginIs401EvenForAStateChangingRequestWithoutToken() throws Exception {
        var beforeLogin = (MockHttpSession) mvc.perform(get("/api/v1/auth/csrf")).andReturn().getRequest().getSession(false);

        assertError(mvc.perform(verify(beforeLogin, "POST")).andReturn(), 401, "COMMON_UNAUTHORIZED");
        assertError(mvc.perform(verify(beforeLogin, "POST").header("Csrf-Token", "stale-token")).andReturn(),
                401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void aSessionWhoseMemberNoLongerExistsIs401() throws Exception {
        assertError(mvc.perform(verify(sessionOf(UUID.randomUUID(), AuthRole.USER), "GET")).andReturn(),
                401, "COMMON_UNAUTHORIZED");
    }

    @ParameterizedTest
    @CsvSource({"SUSPENDED,USER_ACCOUNT_SUSPENDED", "CLOSED,USER_ACCOUNT_CLOSED"})
    void aMemberWhoIsNoLongerActiveIs403WithTheirStatus(UserStatus state, String code) throws Exception {
        var userId = saveMember(state, AuthRole.USER);

        assertError(mvc.perform(verify(sessionOf(userId, AuthRole.USER), "GET")).andReturn(), 403, code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void aReadingOriginalRequestNeedsNoCsrfToken(String originalMethod) throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.USER);

        var result = mvc.perform(verify(sessionOf(userId, AuthRole.USER), originalMethod)).andReturn();

        assertEquals(200, result.getResponse().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void aStateChangingOriginalRequestWithoutTheCsrfTokenIs403(String originalMethod) throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.USER);

        assertError(mvc.perform(verify(sessionOf(userId, AuthRole.USER), originalMethod)).andReturn(),
                403, "COMMON_CSRF_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void aStateChangingOriginalRequestPassesWithTheSessionsCsrfToken(String originalMethod) throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.USER);
        var session = sessionOf(userId, AuthRole.USER);
        var token = csrfToken(session);

        var result = mvc.perform(verify(session, originalMethod).header("Csrf-Token", token)).andReturn();

        assertEquals(200, result.getResponse().getStatus());
    }

    @Test
    void aCsrfTokenFromAnotherSessionIsRejected() throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.USER);
        var otherToken = csrfToken(sessionOf(userId, AuthRole.USER));

        var result = mvc.perform(verify(sessionOf(userId, AuthRole.USER), "POST")
                .header("Csrf-Token", otherToken)).andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "TRACE", "GET,POST", ""})
    void anOriginalMethodTheSpecDoesNotAllowIs400(String originalMethod) throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.USER);

        assertError(mvc.perform(verify(sessionOf(userId, AuthRole.USER), originalMethod)).andReturn(),
                400, "COMMON_INVALID_REQUEST");
    }

    @Test
    void aMissingOrRepeatedOriginalMethodIs400EvenWithoutASession() throws Exception {
        var userId = saveMember(UserStatus.ACTIVE, AuthRole.USER);

        assertError(mvc.perform(post(PATH).session(sessionOf(userId, AuthRole.USER))).andReturn(),
                400, "COMMON_INVALID_REQUEST");
        assertError(mvc.perform(post(PATH).header("Original-Method", "GET", "GET")).andReturn(),
                400, "COMMON_INVALID_REQUEST");
    }

    private MockHttpServletRequestBuilder verify(MockHttpSession session, String originalMethod) {
        return post(PATH).session(session).header("Original-Method", originalMethod);
    }

    /** 로그인 성공 뒤 세션에 저장되는 인증 정보와 같은 모양의 세션을 만듭니다. */
    private MockHttpSession sessionOf(UUID userId, AuthRole roleAtLogin) {
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserSessionPrincipal(userId, roleAtLogin, AT), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + roleAtLogin.name()))));
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    /** 브라우저처럼 CSRF 조회 API에서 그 세션의 토큰을 받습니다. */
    private String csrfToken(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        return mapper.treeToValue(body.get("data"), CsrfTokenResponse.class).token();
    }

    private UUID saveMember(UserStatus state, AuthRole role) {
        var userId = transactions.execute(status -> {
            var user = User.register("홍길동", "010-1800-0001", AT);
            entityManager.persist(user);
            return user.getId();
        });
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state, u.role = :role where u.id = :id")
                .setParameter("state", state).setParameter("role", role).setParameter("id", userId)
                .executeUpdate());
        return userId;
    }

    private void assertError(MvcResult result, int status, String code) throws IOException {
        assertEquals(status, result.getResponse().getStatus());
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        assertEquals(code, body.get("error").get("code").asString());
        assertTrue(body.get("data").isNull());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class,
            CommonWebAutoConfiguration.class, CommonAuthWebAutoConfiguration.class})
    static class WebTestConfiguration {
    }
}
