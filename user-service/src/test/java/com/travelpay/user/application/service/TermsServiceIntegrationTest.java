package com.travelpay.user.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.TermsResult;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import com.travelpay.user.common.config.ClockConfiguration;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.infrastructure.persistence.TermsDocumentRepositoryImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class,
        ClockConfiguration.class,
        PostgresJpaTestConfiguration.class,
        JpaRepositoryTestConfiguration.class,
        TermsService.class,
        TermsServiceIntegrationTest.QueryTestConfiguration.class
})
class TermsServiceIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-26T00:00:00Z");

    @Autowired
    private TermsService termsService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        // 이 클래스 전용 PostgreSQL 테스트 컨텍스트의 약관 데이터만 정리합니다.
        transactions.executeWithoutResult(status -> entityManager.createQuery("delete from TermsDocument")
                .executeUpdate());
    }

    @Test
    void usesTheInjectedClockAndReturnsValuesAfterTheReadOnlyTransactionEnds() {
        transactions.executeWithoutResult(status -> {
            publish("SERVICE", "v9", "이전 약관", "이전 본문", true, AT.minusSeconds(60));
            publish("SERVICE", "v2", "현재 약관", "현재 본문", true, AT);
            publish("SERVICE", "v3", "다음 약관", "다음 본문", true, AT.plusSeconds(60));
            publish("PRIVACY", "v1", "개인정보 약관", "개인정보 본문", false, AT.minusSeconds(30));
            publish("FUTURE_ONLY", "v1", "시행 예정", "미래 본문", true, AT.plusSeconds(60));
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        var results = termsService.getCurrentTerms();

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(List.of(
                new TermsResult("PRIVACY", "v1", "개인정보 약관", "개인정보 본문", false,
                        AT.minusSeconds(30)),
                new TermsResult("SERVICE", "v2", "현재 약관", "현재 본문", true, AT)), results);
    }

    @Test
    void returnsAnEmptyListWhenNoTermsHaveTakenEffectAtTheInjectedTime() {
        transactions.executeWithoutResult(status ->
                publish("SERVICE", "v1", "시행 예정", "약관 본문", true, AT.plusSeconds(1)));

        assertTrue(termsService.getCurrentTerms().isEmpty());
    }

    @Test
    void aDatabaseFailureIsServiceUnavailableRatherThanAnUnexpectedError() {
        renameTable("terms_documents", "unavailable_terms");
        try {
            var failure = assertThrows(BusinessException.class, () -> termsService.getCurrentTerms());
            assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
        }
        finally {
            renameTable("unavailable_terms", "terms_documents");
        }
    }

    private void renameTable(String from, String to) {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service." + from + " rename to " + to).executeUpdate());
    }

    private void publish(String code, String version, String title, String content,
            boolean required, Instant effectiveAt) {
        entityManager.persist(TermsDocument.publish(new TermsDocumentId(code, version),
                title, content, required, effectiveAt));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class QueryTestConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(AT, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        TermsDocumentRepository transactionalTermsRepository(TermsDocumentRepositoryImpl delegate) {
            return at -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return delegate.findEffectiveAt(at);
            };
        }
    }
}
