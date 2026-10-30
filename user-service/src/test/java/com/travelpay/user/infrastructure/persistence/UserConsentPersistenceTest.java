package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserConsent;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.model.vo.UserConsentId;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringJUnitConfig(classes = {JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class})
class UserConsentPersistenceTest {

    private static final Instant EFFECTIVE_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant AGREED_AT = Instant.parse("2026-10-31T00:00:00Z");

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
    void membersAndTermsVersionsHaveIndependentConsentRecordsWithACompositeTermsForeignKey() {
        var firstUserId = saveUser("010-5555-6666");
        var secondUserId = saveUser("010-5555-7777");
        var firstTermsId = new TermsDocumentId("CONSENT_HISTORY_TERMS", "v1");
        var secondTermsId = new TermsDocumentId("CONSENT_HISTORY_TERMS", "v2");
        var secondAgreementTime = Instant.parse("2026-12-01T00:00:00Z");
        saveTerms(firstTermsId, EFFECTIVE_AT);
        saveTerms(secondTermsId, Instant.parse("2026-11-30T00:00:00Z"));

        saveConsent(firstUserId, firstTermsId, AGREED_AT);
        saveConsent(firstUserId, secondTermsId, secondAgreementTime);
        saveConsent(secondUserId, firstTermsId, AGREED_AT);

        transactions.executeWithoutResult(status -> {
            var first = entityManager.find(UserConsent.class, new UserConsentId(firstUserId, firstTermsId));
            var nextVersion = entityManager.find(UserConsent.class, new UserConsentId(firstUserId, secondTermsId));
            var anotherMember = entityManager.find(UserConsent.class, new UserConsentId(secondUserId, firstTermsId));

            assertEquals(firstUserId, first.getUser().getId());
            assertEquals(firstTermsId, first.getTermsDocument().getId());
            assertEquals(AGREED_AT, first.getAgreedAt());
            assertEquals(secondAgreementTime, nextVersion.getAgreedAt());
            assertEquals(secondUserId, anotherMember.getUser().getId());
            assertEquals(2, entityManager.createNativeQuery(
                    "select cardinality(conkey) from pg_constraint "
                            + "where conrelid = 'user_service.user_consents'::regclass "
                            + "and conname = 'fk_user_consents_terms_document' and contype = 'f'",
                    Integer.class).getSingleResult());
        });
    }

    @Test
    void theSameMemberCannotInsertTheSameTermsVersionTwice() {
        var userId = saveUser("010-6666-7777");
        var termsId = new TermsDocumentId("CONSENT_DUPLICATE_TERMS", "v1");
        saveTerms(termsId, EFFECTIVE_AT);
        saveConsent(userId, termsId, AGREED_AT);

        var exception = assertThrows(ConstraintViolationException.class,
                () -> saveConsent(userId, termsId, AGREED_AT.plusSeconds(60)));

        assertEquals("user_consents_pkey", exception.getConstraintName());
    }

    @Test
    void theDatabaseRejectsAnUnpublishedCombinationOfExistingCodeAndVersionValues() {
        var userId = saveUser("010-7777-8888");
        saveTerms(new TermsDocumentId("CONSENT_PAIR_SERVICE", "v1"), EFFECTIVE_AT);
        saveTerms(new TermsDocumentId("CONSENT_PAIR_PRIVACY", "v2"), EFFECTIVE_AT);

        var exception = assertThrows(ConstraintViolationException.class,
                () -> insertDirectly(userId, "CONSENT_PAIR_SERVICE", "v2"));

        assertEquals("fk_user_consents_terms_document", exception.getConstraintName());
    }

    @Test
    void theDatabaseRejectsConsentForANonexistentMember() {
        var termsId = new TermsDocumentId("CONSENT_ORPHAN_TERMS", "v1");
        saveTerms(termsId, EFFECTIVE_AT);

        var exception = assertThrows(ConstraintViolationException.class,
                () -> insertDirectly(UUID.randomUUID(), termsId.termsCode(), termsId.termsVersion()));

        assertEquals("fk_user_consents_user", exception.getConstraintName());
    }

    private UUID saveUser(String phone) {
        return transactions.execute(status -> {
            var user = User.register("홍길동", phone, EFFECTIVE_AT);
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
    }

    private void saveTerms(TermsDocumentId id, Instant effectiveAt) {
        transactions.executeWithoutResult(status -> {
            entityManager.persist(TermsDocument.publish(id, "약관 제목", "약관 원문", true, effectiveAt));
            entityManager.flush();
        });
    }

    private void saveConsent(UUID userId, TermsDocumentId termsId, Instant agreedAt) {
        transactions.executeWithoutResult(status -> {
            var user = entityManager.getReference(User.class, userId);
            var terms = entityManager.find(TermsDocument.class, termsId);
            entityManager.persist(UserConsent.agreeTo(user, terms, agreedAt));
            entityManager.flush();
        });
    }

    private void insertDirectly(UUID userId, String termsCode, String termsVersion) {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                        "insert into user_service.user_consents (user_id, terms_code, terms_version, agreed_at) "
                                + "values (:userId, :termsCode, :termsVersion, :agreedAt)")
                .setParameter("userId", userId)
                .setParameter("termsCode", termsCode)
                .setParameter("termsVersion", termsVersion)
                .setParameter("agreedAt", AGREED_AT)
                .executeUpdate());
    }
}
