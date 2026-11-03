package com.travelpay.user.presentation.controller;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.config.CommonWebAutoConfiguration;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.application.service.UserRealNameService;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.security.SecurityConfiguration;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.infrastructure.redis.RedisRecipientLookupLimiter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringJUnitWebConfig(classes = {
        SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        CurrentMemberService.class, UserRealNameService.class, InternalUserController.class,
        RedisRecipientLookupLimiter.class,
        InternalUserControllerIntegrationTest.WebTestConfiguration.class
})
@TestPropertySource(properties = {
        "travelpay.user.recipient-lookup.limit=60", "travelpay.user.recipient-lookup.period=60s"
})
class InternalUserControllerIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-31T00:00:00Z");

    @Autowired private WebApplicationContext context;
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private StringRedisTemplate recipientLookupRedisTemplate;
    @Autowired @Qualifier("recipientLookupRedis") private GenericContainer<?> recipientLookupRedis;

    private MockMvc mvc;
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private UUID requesterId;

    @BeforeEach
    void setUp() {
        recipientLookupRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
        requesterId = transactions.execute(status -> {
            var requester = User.register("김송금", "010-1800-0002", AT);
            entityManager.persist(requester);
            return requester.getId();
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"real-name", "funding-identity"})
    void anotherServiceReadsAnActiveMembersIdAndRealNameWithoutASession(String lookup) throws Exception {
        var userId = saveMember(UserStatus.ACTIVE);

        var request = lookup.equals("real-name") ? recipientRequest(userId)
                : fundingRequest(userId, userId);
        var result = mvc.perform(request).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        // 응답 전체를 비교해 이메일·휴대폰 번호 같은 다른 개인정보가 들어가지 않았는지도 확인합니다.
        assertEquals(mapper.readTree("""
                {"success": true, "data": {"userId": "%s", "realName": "홍길동"}, "error": null}
                """.formatted(userId)), mapper.readTree(result.getResponse().getContentAsString(UTF_8)));
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        var cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("no-store"), cacheControl);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void aRecipientWhoIsNotActiveIs404LikeAMissingMember(UserStatus state) throws Exception {
        var userId = saveMember(state);

        assertError(mvc.perform(recipientRequest(userId)).andReturn(),
                404, "USER_RECIPIENT_NOT_FOUND");
    }

    @Test
    void sixtyMixedLookupsShareTheRequestersAllowanceAndTheNextIs429() throws Exception {
        var activeId = saveMember(UserStatus.ACTIVE);
        var missingId = UUID.randomUUID();
        for (int request = 0; request < 60; request++) {
            var targetId = request % 2 == 0 ? activeId : missingId;
            int expectedStatus = request % 2 == 0 ? 200 : 404;
            assertEquals(expectedStatus, mvc.perform(recipientRequest(targetId)).andReturn().getResponse().getStatus());
        }

        for (var targetId : List.of(activeId, missingId)) {
            assertGuardError(mvc.perform(recipientRequest(targetId)).andReturn(),
                    429, "COMMON_TOO_MANY_REQUESTS");
        }

        var anotherRequester = get("/internal/v1/users/{userId}/real-name", activeId)
                .header(AuthHeaders.USER_ID, activeId.toString()).header(AuthHeaders.USER_ROLE, "USER");
        assertEquals(200, mvc.perform(anotherRequester).andReturn().getResponse().getStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableRedisIs503ButMissingRequesterHeadersAreStill401(boolean hasRequester) throws Exception {
        var targetId = saveMember(UserStatus.ACTIVE);
        var request = hasRequester ? recipientRequest(targetId)
                : get("/internal/v1/users/{userId}/real-name", targetId);
        recipientLookupRedis.getDockerClient().pauseContainerCmd(recipientLookupRedis.getContainerId()).exec();
        try {
            var result = mvc.perform(request).andReturn();
            assertGuardError(result, hasRequester ? 503 : 401,
                    hasRequester ? "COMMON_SERVICE_UNAVAILABLE" : "COMMON_UNAUTHORIZED");
        }
        finally {
            recipientLookupRedis.getDockerClient().unpauseContainerCmd(recipientLookupRedis.getContainerId()).exec();
        }
    }

    @Test
    void aMissingMemberIs404WithTheCodeOfEachLookup() throws Exception {
        var missing = UUID.randomUUID();

        assertError(mvc.perform(recipientRequest(missing)).andReturn(),
                404, "USER_RECIPIENT_NOT_FOUND");
        assertError(mvc.perform(fundingRequest(missing, missing)).andReturn(),
                404, "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @CsvSource({"SUSPENDED,USER_ACCOUNT_SUSPENDED", "CLOSED,USER_ACCOUNT_CLOSED"})
    void aBlockedMemberGets403WithTheirAccountStatusInsteadOfAFundingIdentity(UserStatus state, String code)
            throws Exception {
        var userId = saveMember(state);

        assertError(mvc.perform(fundingRequest(userId, userId)).andReturn(),
                403, code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"real-name", "funding-identity"})
    void aUserIdThatIsNotAUuidIs400(String lookup) throws Exception {
        assertError(mvc.perform(get("/internal/v1/users/not-a-uuid/" + lookup)).andReturn(),
                400, "COMMON_INVALID_REQUEST");
    }

    @Test
    void aRecipientLookupWithoutRequesterHeadersDoesNotRevealWhetherTheTargetExists() throws Exception {
        for (var targetId : List.of(saveMember(UserStatus.ACTIVE), UUID.randomUUID())) {
            var result = mvc.perform(get("/internal/v1/users/{userId}/real-name", targetId)).andReturn();

            assertRequesterRejected(result);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidRequesterHeaders")
    void anIncompleteOrInvalidRequesterHeaderIsUnauthorized(String userId, String role) throws Exception {
        var targetId = saveMember(UserStatus.ACTIVE);
        var request = get("/internal/v1/users/{userId}/real-name", targetId);
        if (userId != null) {
            request.header(AuthHeaders.USER_ID, userId);
        }
        if (role != null) {
            request.header(AuthHeaders.USER_ROLE, role);
        }

        assertRequesterRejected(mvc.perform(request).andReturn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"User-Id", "User-Role"})
    void duplicatedRequesterHeadersAreUnauthorizedEvenWhenTheValuesMatch(String header) throws Exception {
        var targetId = saveMember(UserStatus.ACTIVE);
        var request = recipientRequest(targetId)
                .header(header, header.equals(AuthHeaders.USER_ID) ? requesterId.toString() : "USER");

        assertRequesterRejected(mvc.perform(request).andReturn());
    }

    @Test
    void aFundingIdentityLookupWithoutRequesterHeadersIsUnauthorized() throws Exception {
        var targetId = saveMember(UserStatus.ACTIVE);

        assertRequesterRejected(mvc.perform(get("/internal/v1/users/{userId}/funding-identity", targetId)).andReturn());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anotherMembersFundingIdentityIsForbiddenWhetherOrNotTheTargetExists(boolean targetExists) throws Exception {
        var targetId = targetExists ? saveMember(UserStatus.ACTIVE) : UUID.randomUUID();

        var result = mvc.perform(fundingRequest(requesterId, targetId)).andReturn();

        assertError(result, 403, "COMMON_FORBIDDEN");
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void aMasterRoleDoesNotAllowAnotherMembersFundingIdentity() throws Exception {
        var targetId = saveMember(UserStatus.ACTIVE);
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", AuthRole.MASTER).setParameter("id", requesterId).executeUpdate());

        var request = get("/internal/v1/users/{userId}/funding-identity", targetId)
                .header(AuthHeaders.USER_ID, requesterId.toString())
                .header(AuthHeaders.USER_ROLE, AuthRole.MASTER.name());

        assertError(mvc.perform(request).andReturn(), 403, "COMMON_FORBIDDEN");
    }

    private static Stream<Arguments> invalidRequesterHeaders() {
        var id = "550e8400-e29b-41d4-a716-446655440000";
        return Stream.of(
                Arguments.of(null, "USER"),
                Arguments.of("", "USER"),
                Arguments.of("not-a-uuid", "USER"),
                Arguments.of("1-1-1-1-1", "USER"),
                Arguments.of(id, null),
                Arguments.of(id, ""),
                Arguments.of(id, "ADMIN"));
    }

    private MockHttpServletRequestBuilder recipientRequest(UUID targetId) {
        return get("/internal/v1/users/{userId}/real-name", targetId)
                .header(AuthHeaders.USER_ID, requesterId.toString())
                .header(AuthHeaders.USER_ROLE, "USER");
    }

    private MockHttpServletRequestBuilder fundingRequest(UUID requesterId, UUID targetId) {
        return get("/internal/v1/users/{userId}/funding-identity", targetId)
                .header(AuthHeaders.USER_ID, requesterId.toString())
                .header(AuthHeaders.USER_ROLE, "USER");
    }

    private void assertRequesterRejected(MvcResult result) throws IOException {
        assertGuardError(result, 401, "COMMON_UNAUTHORIZED");
    }

    private void assertGuardError(MvcResult result, int status, String code) throws IOException {
        assertError(result, status, code);
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        var cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("no-store"));
    }

    private UUID saveMember(UserStatus state) {
        var userId = transactions.execute(status -> {
            var user = User.register("홍길동", "010-1800-0001", AT);
            entityManager.persist(user);
            return user.getId();
        });
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());
        return userId;
    }

    private void assertError(MvcResult result, int status, String code) throws IOException {
        assertEquals(status, result.getResponse().getStatus());
        var text = result.getResponse().getContentAsString(UTF_8);
        var body = mapper.readTree(text);
        assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("data").isNull());
        assertEquals(code, body.get("error").get("code").asString());
        assertFalse(text.contains("홍길동"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class,
            CommonWebAutoConfiguration.class})
    static class WebTestConfiguration {

        @Bean(destroyMethod = "stop")
        GenericContainer<?> recipientLookupRedis() {
            var container = new GenericContainer<>(DockerImageName.parse("redis:8.2.11-bookworm"))
                    .withExposedPorts(6379).withCommand("redis-server", "--appendonly", "no", "--save", "");
            container.start();
            return container;
        }

        @Bean
        LettuceConnectionFactory recipientLookupConnectionFactory(
                @Qualifier("recipientLookupRedis") GenericContainer<?> container) {
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1))
                    .shutdownTimeout(Duration.ofMillis(100)).build();
            return new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)), client);
        }

        @Bean
        StringRedisTemplate recipientLookupRedisTemplate(LettuceConnectionFactory recipientLookupConnectionFactory) {
            return new StringRedisTemplate(recipientLookupConnectionFactory);
        }
    }
}
