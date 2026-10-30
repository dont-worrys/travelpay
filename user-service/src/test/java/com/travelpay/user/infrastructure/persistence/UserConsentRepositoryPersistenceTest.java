package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserConsent;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.model.vo.UserConsentId;
import com.travelpay.user.domain.repository.UserConsentRepository;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TransactionRequiredException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class,
        JpaRepositoryTestConfiguration.class
})
class UserConsentRepositoryPersistenceTest {

    private static final Instant EFFECTIVE_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant AGREED_AT = Instant.parse("2026-10-31T00:00:00Z");

    @Autowired
    private UserConsentRepository consentRepository;

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
    void insertsTwoNewConsentsWithoutSelectingTheirAssignedIdentifiers() {
        var fixture = prepare("CREATE", "010-1400-0001");

        transactions.executeWithoutResult(status -> {
            var first = newConsent(fixture, fixture.firstTermsId(), AGREED_AT);
            var second = newConsent(fixture, fixture.secondTermsId(), AGREED_AT);
            var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            var statisticsWereEnabled = statistics.isStatisticsEnabled();
            statistics.setStatisticsEnabled(true);
            statistics.clear();

            try {
                var saved = consentRepository.saveAll(List.of(first, second));
                entityManager.flush();

                assertEquals(2, saved.size());
                assertEquals(2L, statistics.getEntityInsertCount());
                assertEquals(2L, statistics.getPrepareStatementCount());
            } finally {
                statistics.setStatisticsEnabled(statisticsWereEnabled);
            }
        });

        transactions.executeWithoutResult(status -> {
            assertEquals(AGREED_AT, find(fixture, fixture.firstTermsId()).getAgreedAt());
            assertEquals(AGREED_AT, find(fixture, fixture.secondTermsId()).getAgreedAt());
        });
    }

    @Test
    void rejectsANewRecordWithAnExistingConsentKeyAndPreservesTheOriginalTime() {
        var fixture = prepare("DUPLICATE", "010-1400-0002");
        save(fixture, fixture.firstTermsId(), AGREED_AT);

        var exception = assertThrows(DataIntegrityViolationException.class,
                () -> save(fixture, fixture.firstTermsId(), AGREED_AT.plusSeconds(60)));

        assertTrue(exception.getMostSpecificCause().getMessage().contains("user_consents_pkey"));
        transactions.executeWithoutResult(status ->
                assertEquals(AGREED_AT, find(fixture, fixture.firstTermsId()).getAgreedAt()));
    }

    @Test
    void rollsBackTheWholeBatchWhenALaterConsentHasADuplicateKey() {
        var fixture = prepare("ATOMIC", "010-1400-0003");
        save(fixture, fixture.firstTermsId(), AGREED_AT);

        assertThrows(DataIntegrityViolationException.class, () -> transactions.executeWithoutResult(status ->
                consentRepository.saveAll(List.of(
                        newConsent(fixture, fixture.secondTermsId(), AGREED_AT),
                        newConsent(fixture, fixture.firstTermsId(), AGREED_AT.plusSeconds(60))))));

        transactions.executeWithoutResult(status -> {
            assertNotNull(find(fixture, fixture.firstTermsId()));
            assertNull(find(fixture, fixture.secondTermsId()));
        });
    }

    @Test
    void springDataSaveMergesAnExistingConsentInsteadOfRejectingANewDuplicate() {
        var fixture = prepare("MERGE", "010-1400-0004");
        save(fixture, fixture.firstTermsId(), AGREED_AT);

        // 복합키가 이미 있는 새 객체를 save하면 신규 INSERT가 아닌 merge 경로를 탑니다.
        assertDoesNotThrow(() -> transactions.executeWithoutResult(status -> {
            var duplicate = newConsent(fixture, fixture.firstTermsId(), AGREED_AT.plusSeconds(60));
            new SimpleJpaRepository<>(UserConsent.class, entityManager).save(duplicate);
            entityManager.flush();
        }));

        transactions.executeWithoutResult(status -> {
            assertEquals(AGREED_AT, find(fixture, fixture.firstTermsId()).getAgreedAt());
            assertEquals(1L, entityManager.createQuery(
                            "select count(consent) from UserConsent consent where consent.id.userId = :userId",
                            Long.class)
                    .setParameter("userId", fixture.userId()).getSingleResult());
        });
    }

    @Test
    void requiresTheCallingApplicationToProvideATransaction() {
        var fixture = prepare("TRANSACTION", "010-1400-0005");
        var consent = transactions.execute(status -> newConsent(fixture, fixture.firstTermsId(), AGREED_AT));

        assertThrows(TransactionRequiredException.class,
                () -> consentRepository.saveAll(List.of(consent)));

        transactions.executeWithoutResult(status -> assertNull(find(fixture, fixture.firstTermsId())));
    }

    private Fixture prepare(String suffix, String phone) {
        return transactions.execute(status -> {
            var user = User.register("홍길동", phone, EFFECTIVE_AT);
            var firstId = new TermsDocumentId("CONSENT_REPO_" + suffix + "_A", "v1");
            var secondId = new TermsDocumentId("CONSENT_REPO_" + suffix + "_B", "v1");
            entityManager.persist(user);
            entityManager.persist(TermsDocument.publish(firstId, "약관 A", "본문 A", true, EFFECTIVE_AT));
            entityManager.persist(TermsDocument.publish(secondId, "약관 B", "본문 B", true, EFFECTIVE_AT));
            entityManager.flush();
            return new Fixture(user.getId(), firstId, secondId);
        });
    }

    private UserConsent newConsent(Fixture fixture, TermsDocumentId termsId, Instant agreedAt) {
        return UserConsent.agreeTo(entityManager.find(User.class, fixture.userId()),
                entityManager.find(TermsDocument.class, termsId), agreedAt);
    }

    private void save(Fixture fixture, TermsDocumentId termsId, Instant agreedAt) {
        transactions.executeWithoutResult(status ->
                consentRepository.saveAll(List.of(newConsent(fixture, termsId, agreedAt))));
    }

    private UserConsent find(Fixture fixture, TermsDocumentId termsId) {
        return entityManager.find(UserConsent.class, new UserConsentId(fixture.userId(), termsId));
    }

    private record Fixture(UUID userId, TermsDocumentId firstTermsId, TermsDocumentId secondTermsId) {
    }
}
