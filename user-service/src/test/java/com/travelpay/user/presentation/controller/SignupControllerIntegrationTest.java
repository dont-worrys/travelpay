package com.travelpay.user.presentation.controller;

import java.io.IOException;
import java.net.HttpCookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.config.CommonAuthWebAutoConfiguration;
import com.travelpay.common.config.CommonWebAutoConfiguration;
import com.travelpay.user.application.service.SignupService;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.common.config.ClockConfiguration;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.security.SecurityConfiguration;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.presentation.dto.CsrfTokenResponse;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(classes = {
        SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, ClockConfiguration.class, JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        SignupService.class, CurrentMemberService.class, SignupController.class, CsrfController.class,
        SignupControllerIntegrationTest.WebTestConfiguration.class
})
@TestPropertySource(properties = {
        "spring.session.timeout=60m",
        "spring.session.data.redis.namespace=travelpay:user:session:test",
        "travelpay.auth.header-context.enabled=false"
})
class SignupControllerIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final String EMAIL = "signup.member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String PHONE = "010-2222-3333";

    @Autowired private WebApplicationContext context;
    @Autowired private JsonMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SessionRepositoryFilter<?> sessionFilter;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mvc;
    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionFilter).apply(springSecurity()).build();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        // 테스트 전용 PostgreSQL의 행만 FK 역순으로 정리합니다.
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            publish("SERVICE_TERMS", AT.minusSeconds(60));
            publish("PRIVACY_TERMS", AT.minusSeconds(60));
        });
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void registersWithRedisBackedCsrfAndReturnsCreatedWithoutLoggingIn() throws Exception {
        var csrf = csrf();
        var result = signup(request(), csrf);

        assertEquals(201, result.getResponse().getStatus());
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        var data = body.get("data");
        assertEquals(3, body.size());
        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("error").isNull());
        assertEquals(3, data.size());
        assertEquals("ACTIVE", data.get("status").asString());
        assertTrue(data.get("loginRequired").asBoolean());
        var userId = UUID.fromString(data.get("userId").asString());
        transactions.executeWithoutResult(status -> {
            var user = entityManager.find(User.class, userId);
            var credentials = entityManager.find(UserCredentials.class, userId);
            assertNotNull(user);
            assertEquals("김여행", user.getRealName());
            assertEquals(PHONE, user.getPhone());
            assertEquals(AuthRole.USER, user.getRole());
            assertEquals(AT, user.getPhoneVerifiedAt());
            assertEquals(EMAIL, credentials.getLoginEmail());
            assertTrue(passwordEncoder.matches(PASSWORD, credentials.getPasswordHash()));
        });
        assertRows(1, 1, 2);
        mvc.perform(get("/api/v1/users/me").cookie(csrf.cookie())).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "입력 {index}: {0}")
    @MethodSource("invalidFields")
    void rejectsInvalidInputWithFieldDetailsAndNoWrites(String field, String value) throws Exception {
        var body = request().put(field, value);

        var result = signup(body, csrf());

        assertError(result, 400, "COMMON_INVALID_REQUEST");
        var details = mapper.readTree(result.getResponse().getContentAsString(UTF_8))
                .get("error").get("details");
        assertTrue(details.valueStream().anyMatch(detail -> detail.get("field").asString().equals(field)));
        assertRows(0, 0, 0);
    }

    @Test
    void validatesNestedConsentBeforeConvertingItToADomainId() throws Exception {
        var body = request();
        ((ObjectNode) body.get("consents").get(0)).put("termsVersion", " ");

        var result = signup(body, csrf());

        assertError(result, 400, "COMMON_INVALID_REQUEST");
        assertTrue(mapper.readTree(result.getResponse().getContentAsString(UTF_8))
                .get("error").get("details").valueStream().anyMatch(detail ->
                        detail.get("field").asString().equals("consents[0].termsVersion")));
        assertRows(0, 0, 0);
    }

    @Test
    void rejectsMissingRequiredConsentWithABusinessError() throws Exception {
        var body = request();
        body.putArray("consents").addObject().put("termsCode", "SERVICE_TERMS").put("termsVersion", "v1");

        assertError(signup(body, csrf()), 400, "USER_TERMS_CONSENT_INVALID");
        assertRows(0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "phone"})
    void returnsConflictWithoutCreatingPartialRows(String duplicate) throws Exception {
        var csrf = csrf();
        assertEquals(201, signup(request(), csrf).getResponse().getStatus());
        var body = request();
        if (duplicate.equals("email")) { body.put("phone", "010-2222-4444"); }
        else { body.put("loginEmail", "different@example.com"); }

        var result = signup(body, csrf);

        assertError(result, 409, duplicate.equals("email")
                ? "USER_EMAIL_ALREADY_EXISTS" : "USER_PHONE_ALREADY_EXISTS");
        assertRows(1, 1, 2);
    }

    @Test
    void rejectsMissingCsrfEvenForPublicSignup() throws Exception {
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/users").cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request())))
                .andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID");
        assertRows(0, 0, 0);
    }

    @Test
    void rejectsACsrfTokenFromAnotherRedisSession() throws Exception {
        var first = csrf();
        var second = csrf();
        var result = mvc.perform(post("/api/v1/users").cookie(first.cookie())
                        .header(second.headerName(), second.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request())))
                .andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID");
        assertRows(0, 0, 0);
    }

    @Test
    void rejectsMalformedJsonWithTheCommonErrorEnvelope() throws Exception {
        var csrf = csrf();
        var result = mvc.perform(post("/api/v1/users").cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEmail\":"))
                .andReturn();

        assertError(result, 400, "COMMON_INVALID_REQUEST");
        assertRows(0, 0, 0);
    }

    @Test
    void returnsServiceUnavailableWhenNoTermsHaveTakenEffect() throws Exception {
        transactions.executeWithoutResult(status -> {
            entityManager.createQuery("delete from TermsDocument").executeUpdate();
            publish("SERVICE_TERMS", AT.plusSeconds(1));
        });

        assertError(signup(request(), csrf()), 503, "COMMON_SERVICE_UNAVAILABLE");
        assertRows(0, 0, 0);
    }

    @Test
    void aDatabaseFailureReturns503WithoutSavingAnything() throws Exception {
        var csrf = csrf();
        renameTable("user_credentials", "unavailable_credentials");
        try {
            assertError(signup(request(), csrf), 503, "COMMON_SERVICE_UNAVAILABLE");
        }
        finally {
            renameTable("unavailable_credentials", "user_credentials");
        }
        assertRows(0, 0, 0);
    }

    private void renameTable(String from, String to) {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service." + from + " rename to " + to).executeUpdate());
    }

    private CsrfClient csrf() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var data = mapper.readTree(result.getResponse().getContentAsString(UTF_8)).get("data");
        var token = mapper.treeToValue(data, CsrfTokenResponse.class);
        var cookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow();
        return new CsrfClient(new Cookie(cookie.getName(), cookie.getValue()), token.headerName(), token.token());
    }

    private MvcResult signup(ObjectNode body, CsrfClient csrf) throws Exception {
        return mvc.perform(post("/api/v1/users").cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(body)))
                .andReturn();
    }

    private ObjectNode request() {
        var body = mapper.createObjectNode().put("loginEmail", "  Signup.Member@Example.COM  ")
                .put("password", PASSWORD).put("realName", "  김여행  ").put("phone", PHONE);
        var consents = body.putArray("consents");
        consents.addObject().put("termsCode", " SERVICE_TERMS ").put("termsVersion", " v1 ");
        consents.addObject().put("termsCode", "PRIVACY_TERMS").put("termsVersion", "v1");
        return body;
    }

    private void assertError(MvcResult result, int status, String code) throws IOException {
        assertEquals(status, result.getResponse().getStatus());
        var text = result.getResponse().getContentAsString(UTF_8);
        var body = mapper.readTree(text);
        assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("data").isNull());
        assertEquals(code, body.get("error").get("code").asString());
        assertTrue(body.get("error").get("details").isArray());
        for (var input : List.of(EMAIL, PASSWORD, PHONE, "김여행")) { assertFalse(text.contains(input)); }
    }

    private void assertRows(long users, long credentials, long consents) {
        transactions.executeWithoutResult(status -> {
            assertEquals(users, count("User"));
            assertEquals(credentials, count("UserCredentials"));
            assertEquals(consents, count("UserConsent"));
        });
    }

    private long count(String entity) {
        return entityManager.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
    }

    private void publish(String code, Instant effectiveAt) {
        entityManager.persist(TermsDocument.publish(new TermsDocumentId(code, "v1"),
                "필수 약관", "약관 본문", true, effectiveAt));
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("loginEmail", "not-an-email"),
                Arguments.of("loginEmail", "\"a b\"@example.com"),
                Arguments.of("loginEmail", "\"a@b\"@example.com"),
                Arguments.of("loginEmail", "a" + Character.toString(0x0085) + "b@example.com"),
                Arguments.of("password", "weak1!"),
                Arguments.of("phone", "01022223333"),
                Arguments.of("realName", " "));
    }

    private record CsrfClient(Cookie cookie, String headerName, String token) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class,
            SessionDataRedisAutoConfiguration.class, SessionAutoConfiguration.class,
            CommonWebAutoConfiguration.class, CommonAuthWebAutoConfiguration.class})
    static class WebTestConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(AT, ZoneOffset.UTC); }

        @Bean RedisPassword sessionPassword() { return RedisPassword.of(UUID.randomUUID().toString()); }

        @Bean(destroyMethod = "stop")
        GenericContainer<?> sessionRedis(RedisPassword sessionPassword) {
            var redis = new GenericContainer<>(DockerImageName.parse("redis:8.2.11-bookworm"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", new String(sessionPassword.get()),
                            "--appendonly", "no", "--save", "");
            redis.start();
            return redis;
        }

        @Bean
        LettuceConnectionFactory sessionConnectionFactory(GenericContainer<?> sessionRedis,
                RedisPassword sessionPassword) {
            var configuration = new RedisStandaloneConfiguration(sessionRedis.getHost(), sessionRedis.getMappedPort(6379));
            configuration.setPassword(sessionPassword);
            return new LettuceConnectionFactory(configuration);
        }
    }
}
