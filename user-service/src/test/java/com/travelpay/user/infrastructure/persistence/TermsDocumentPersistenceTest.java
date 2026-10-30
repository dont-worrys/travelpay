package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;

import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class})
class TermsDocumentPersistenceTest {

    private static final Instant EFFECTIVE_AT = Instant.parse("2026-10-30T00:00:00Z");

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
    }

    @Test
    void versionsOfTheSameCodeCoexistAndPreserveTheirLongTextContent() {
        var firstId = new TermsDocumentId("PERSISTENCE_TERMS", "v1");
        var secondId = new TermsDocumentId("PERSISTENCE_TERMS", "v2");
        var content = "\n약관 본문\n".repeat(1000);
        save(TermsDocument.publish(firstId, "첫 버전", content, true, EFFECTIVE_AT));
        save(TermsDocument.publish(secondId, "두 번째 버전", "새 약관 본문", false,
                Instant.parse("2026-11-30T00:00:00Z")));

        transactions.executeWithoutResult(status -> {
            var first = entityManager.find(TermsDocument.class, new TermsDocumentId("PERSISTENCE_TERMS", "v1"));
            var second = entityManager.find(TermsDocument.class, secondId);

            assertEquals(firstId, first.getId());
            assertEquals(content, first.getContent());
            assertTrue(first.isRequired());
            assertEquals(EFFECTIVE_AT, first.getEffectiveAt());
            assertEquals("새 약관 본문", second.getContent());
            assertFalse(second.isRequired());
            assertEquals("text", entityManager.createNativeQuery(
                    "select data_type from information_schema.columns "
                            + "where table_schema = 'user_service' and table_name = 'terms_documents' "
                            + "and column_name = 'content'", String.class).getSingleResult());
        });
    }

    @Test
    void duplicateCodeAndVersionCannotBeInserted() {
        var id = new TermsDocumentId("DUPLICATE_TERMS", "v1");
        save(TermsDocument.publish(id, "첫 게시", "원래 약관", true, EFFECTIVE_AT));

        var exception = assertThrows(ConstraintViolationException.class,
                () -> save(TermsDocument.publish(
                        new TermsDocumentId("DUPLICATE_TERMS", "v1"), "중복 게시", "다른 본문", true, EFFECTIVE_AT)));

        assertEquals("terms_documents_pkey", exception.getConstraintName());
    }

    @Test
    void ordinaryJpaDirtyCheckingDoesNotOverwritePublishedVersionData() {
        var id = new TermsDocumentId("IMMUTABLE_TERMS", "v1");
        save(TermsDocument.publish(id, "원래 제목", "원래 약관 본문", true, EFFECTIVE_AT));

        transactions.executeWithoutResult(status -> {
            var stored = entityManager.find(TermsDocument.class, id);
            // 테스트에서만 값을 바꿔 일반 JPA 수정 경로의 덮어쓰기 방지를 확인합니다.
            ReflectionTestUtils.setField(stored, "title", "덮어쓴 제목");
            ReflectionTestUtils.setField(stored, "content", "덮어쓴 본문");
            ReflectionTestUtils.setField(stored, "required", false);
            ReflectionTestUtils.setField(stored, "effectiveAt", Instant.parse("2026-12-30T00:00:00Z"));
            entityManager.flush();
        });

        transactions.executeWithoutResult(status -> {
            var stored = entityManager.find(TermsDocument.class, id);

            assertEquals("원래 제목", stored.getTitle());
            assertEquals("원래 약관 본문", stored.getContent());
            assertTrue(stored.isRequired());
            assertEquals(EFFECTIVE_AT, stored.getEffectiveAt());
        });
    }

    private void save(TermsDocument document) {
        transactions.executeWithoutResult(status -> {
            entityManager.persist(document);
            entityManager.flush();
        });
    }
}
