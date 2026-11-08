package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class,
        JpaRepositoryTestConfiguration.class
})
@Transactional
class TermsDocumentRepositoryPersistenceTest {

    private static final Instant AT = Instant.parse("2026-10-26T00:00:00Z");

    @Autowired
    private TermsDocumentRepository termsRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void selectsTheLatestEffectiveVersionPerCodeInCodeOrder() {
        publish("REPO_CURRENT_B", "v1", AT.minusSeconds(200));
        publish("REPO_CURRENT_A", "v9", AT.minusSeconds(300));
        publish("REPO_CURRENT_A", "v2", AT.minusSeconds(100));
        publish("REPO_CURRENT_A", "v3", AT.plusSeconds(100));
        publish("REPO_FUTURE_ONLY", "v1", AT.plusSeconds(100));

        var found = termsRepository.findEffectiveAt(AT);

        assertEquals(List.of(
                new TermsDocumentId("REPO_CURRENT_A", "v2"),
                new TermsDocumentId("REPO_CURRENT_B", "v1")),
                found.stream().map(TermsDocument::getId).toList());
    }

    @Test
    void includesAVersionAtItsEffectiveTimeButNotBeforeIt() {
        var terms = publish("REPO_BOUNDARY", "v1", AT);

        assertTrue(termsRepository.findEffectiveAt(AT.minusSeconds(1)).isEmpty());
        assertEquals(List.of(terms.getId()), termsRepository.findEffectiveAt(AT).stream()
                .map(TermsDocument::getId).toList());
    }

    @Test
    void rejectsTwoVersionsOfTheSameCodeAtTheSameEffectiveTime() {
        publish("REPO_SAME_EFFECTIVE_TIME", "v1", AT);

        var exception = assertThrows(ConstraintViolationException.class,
                () -> publish("REPO_SAME_EFFECTIVE_TIME", "v2", AT));

        assertEquals("uk_terms_documents_code_effective_at", exception.getConstraintName());
    }

    @Test
    void allowsDifferentCodesToTakeEffectAtTheSameTime() {
        var first = publish("REPO_SHARED_TIME_A", "v1", AT);
        var second = publish("REPO_SHARED_TIME_B", "v1", AT);

        assertEquals(List.of(first.getId(), second.getId()), termsRepository.findEffectiveAt(AT).stream()
                .map(TermsDocument::getId).toList());
    }

    @Test
    void listsVersionsByCodeAndTheLatestEffectiveTimeFirst() {
        publish("REPO_PAGE_B", "v1", AT);
        publish("REPO_PAGE_A", "v1", AT.minusSeconds(100));
        publish("REPO_PAGE_A", "v3", AT.plusSeconds(100));
        publish("REPO_PAGE_A", "v2", AT);

        var listed = termsRepository.findPage(null, 0, 1000).stream().map(TermsDocument::getId)
                .filter(id -> id.termsCode().startsWith("REPO_PAGE_")).toList();

        assertEquals(List.of(new TermsDocumentId("REPO_PAGE_A", "v3"), new TermsDocumentId("REPO_PAGE_A", "v2"),
                new TermsDocumentId("REPO_PAGE_A", "v1"), new TermsDocumentId("REPO_PAGE_B", "v1")), listed);
    }

    @Test
    void listsOneCodeOnlyAndReturnsTheRequestedSlice() {
        publish("REPO_SLICE", "v1", AT.minusSeconds(200));
        publish("REPO_SLICE", "v2", AT.minusSeconds(100));
        publish("REPO_SLICE", "v3", AT);
        publish("REPO_SLICE_OTHER", "v1", AT);

        assertEquals(List.of(new TermsDocumentId("REPO_SLICE", "v2"), new TermsDocumentId("REPO_SLICE", "v1")),
                termsRepository.findPage("REPO_SLICE", 1, 2).stream().map(TermsDocument::getId).toList());
    }

    @Test
    void insertAddsANewVersion() {
        var terms = TermsDocument.publish(new TermsDocumentId("REPO_INSERT", "v1"), "약관 제목", "약관 본문", true, AT);

        termsRepository.insert(terms);

        assertEquals(List.of(terms.getId()),
                termsRepository.findPage("REPO_INSERT", 0, 10).stream().map(TermsDocument::getId).toList());
    }

    @ParameterizedTest
    @CsvSource({"v1,60", "v2,0"})
    void insertRejectsAnExistingVersionOrEffectiveTimeOfTheSameCode(String version, long secondsLater) {
        publish("REPO_INSERT_CONFLICT", "v1", AT);
        // 이 트랜잭션이 들고 있는 엔티티가 아니라 DB 제약(기본키·코드+시행 시각)으로 거절되는지 확인
        entityManager.clear();
        var conflicting = TermsDocument.publish(new TermsDocumentId("REPO_INSERT_CONFLICT", version),
                "바꾼 제목", "바꾼 본문", false, AT.plusSeconds(secondsLater));

        var exception = assertThrows(BusinessException.class, () -> termsRepository.insert(conflicting));

        assertEquals(UserErrorCode.TERMS_VERSION_CONFLICT, exception.getErrorCode());
    }

    @Test
    void insertRejectsAVersionThisTransactionAlreadyHolds() {
        publish("REPO_INSERT_HELD", "v1", AT);
        var sameVersion = TermsDocument.publish(new TermsDocumentId("REPO_INSERT_HELD", "v1"),
                "바꾼 제목", "바꾼 본문", false, AT.plusSeconds(60));

        var exception = assertThrows(BusinessException.class, () -> termsRepository.insert(sameVersion));

        assertEquals(UserErrorCode.TERMS_VERSION_CONFLICT, exception.getErrorCode());
    }

    private TermsDocument publish(String code, String version, Instant effectiveAt) {
        var terms = TermsDocument.publish(new TermsDocumentId(code, version),
                "약관 제목", "약관 본문", true, effectiveAt);
        entityManager.persist(terms);
        entityManager.flush();
        return terms;
    }
}
