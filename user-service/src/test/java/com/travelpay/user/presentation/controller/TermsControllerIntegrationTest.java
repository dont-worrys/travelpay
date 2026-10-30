package com.travelpay.user.presentation.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.travelpay.user.application.service.TermsService;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.common.config.ClockConfiguration;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.common.security.SecurityConfiguration;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(classes = {
        SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        ClockConfiguration.class,
        JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class,
        JpaRepositoryTestConfiguration.class,
        TermsService.class,
        CurrentMemberService.class,
        TermsController.class,
        TermsControllerIntegrationTest.WebTestConfiguration.class
})
class TermsControllerIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-26T00:00:00Z");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mvc;
    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        // 이 테스트 클래스의 PostgreSQL 컨테이너에 있는 약관 데이터만 정리합니다.
        transactions.executeWithoutResult(status -> entityManager.createQuery("delete from TermsDocument")
                .executeUpdate());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousClientReadsCurrentTermsAsAUniformJsonResponseWithUtcTimes() throws Exception {
        transactions.executeWithoutResult(status -> {
            publish("SERVICE_TERMS", "v1", "이전 서비스 약관", "이전 본문", true, AT.minusSeconds(60));
            publish("SERVICE_TERMS", "v2", "서비스 이용약관", "현재 서비스 약관 본문", true, AT);
            publish("SERVICE_TERMS", "v3", "시행 예정 약관", "다음 본문", true, AT.plusSeconds(60));
            publish("PRIVACY_TERMS", "v1", "개인정보 약관", "개인정보 본문", false, AT.minusSeconds(60));
        });

        var result = mvc.perform(get("/api/v1/terms").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertEquals(mapper.readTree("""
                {
                  "success": true,
                  "data": {
                    "terms": [
                      {
                        "termsCode": "PRIVACY_TERMS",
                        "termsVersion": "v1",
                        "title": "개인정보 약관",
                        "content": "개인정보 본문",
                        "isRequired": false,
                        "effectiveAt": "2026-10-25T23:59:00Z"
                      },
                      {
                        "termsCode": "SERVICE_TERMS",
                        "termsVersion": "v2",
                        "title": "서비스 이용약관",
                        "content": "현재 서비스 약관 본문",
                        "isRequired": true,
                        "effectiveAt": "2026-10-26T00:00:00Z"
                      }
                    ]
                  },
                  "error": null
                }
                """), mapper.readTree(result.getResponse().getContentAsString(UTF_8)));
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void anonymousClientReceivesAnEmptyTermsArrayWhenNothingHasTakenEffect() throws Exception {
        transactions.executeWithoutResult(status ->
                publish("SERVICE_TERMS", "v1", "시행 예정 약관", "약관 본문", true, AT.plusSeconds(1)));

        var result = mvc.perform(get("/api/v1/terms").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertEquals(mapper.readTree("""
                {"success":true,"data":{"terms":[]},"error":null}
                """), mapper.readTree(result.getResponse().getContentAsString(UTF_8)));
    }

    private void publish(String code, String version, String title, String content,
            boolean required, Instant effectiveAt) {
        entityManager.persist(TermsDocument.publish(new TermsDocumentId(code, version),
                title, content, required, effectiveAt));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class})
    static class WebTestConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(AT, ZoneOffset.UTC);
        }
    }
}
