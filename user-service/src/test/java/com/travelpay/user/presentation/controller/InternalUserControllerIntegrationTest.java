package com.travelpay.user.presentation.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
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
        InternalUserControllerIntegrationTest.WebTestConfiguration.class
})
class InternalUserControllerIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-31T00:00:00Z");

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

    @ParameterizedTest
    @ValueSource(strings = {"real-name", "funding-identity"})
    void anotherServiceReadsAnActiveMembersIdAndRealNameWithoutASession(String lookup) throws Exception {
        var userId = saveMember(UserStatus.ACTIVE);

        var result = mvc.perform(get("/internal/v1/users/{userId}/" + lookup, userId)).andReturn();

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

        assertError(mvc.perform(get("/internal/v1/users/{userId}/real-name", userId)).andReturn(),
                404, "USER_RECIPIENT_NOT_FOUND");
    }

    @Test
    void aMissingMemberIs404WithTheCodeOfEachLookup() throws Exception {
        var missing = UUID.randomUUID();

        assertError(mvc.perform(get("/internal/v1/users/{userId}/real-name", missing)).andReturn(),
                404, "USER_RECIPIENT_NOT_FOUND");
        assertError(mvc.perform(get("/internal/v1/users/{userId}/funding-identity", missing)).andReturn(),
                404, "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @CsvSource({"SUSPENDED,USER_ACCOUNT_SUSPENDED", "CLOSED,USER_ACCOUNT_CLOSED"})
    void aBlockedMemberGets403WithTheirAccountStatusInsteadOfAFundingIdentity(UserStatus state, String code)
            throws Exception {
        var userId = saveMember(state);

        assertError(mvc.perform(get("/internal/v1/users/{userId}/funding-identity", userId)).andReturn(),
                403, code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"real-name", "funding-identity"})
    void aUserIdThatIsNotAUuidIs400(String lookup) throws Exception {
        assertError(mvc.perform(get("/internal/v1/users/not-a-uuid/" + lookup)).andReturn(),
                400, "COMMON_INVALID_REQUEST");
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
    }
}
