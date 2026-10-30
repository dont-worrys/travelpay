package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringJUnitConfig(classes = {JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class})
class UserCredentialsPersistenceTest {

    private static final Instant PASSWORD_SET_AT = Instant.parse("2026-10-29T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-29T00:00:10Z");
    private static final String PASSWORD_HASH =
            "{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditingHandler auditingHandler;

    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        auditingHandler.setDateTimeProvider(() -> Optional.of(CREATED_AT));
    }

    @Test
    void persistenceSharesTheMemberPrimaryKeyAndRecordsTheCredentialCreationTime() {
        var userId = saveCredentials("010-2222-3333", " Member.Persistence@Example.COM ");

        transactions.executeWithoutResult(status -> {
            var stored = entityManager.find(UserCredentials.class, userId);

            assertNotNull(stored);
            assertEquals(userId, stored.getUserId());
            assertEquals(userId, stored.getUser().getId());
            assertEquals("member.persistence@example.com", stored.getLoginEmail());
            assertEquals(PASSWORD_HASH, stored.getPasswordHash());
            assertEquals(PASSWORD_SET_AT, stored.getPasswordChangedAt());
            assertEquals(CREATED_AT, stored.getCreatedAt());
        });
    }

    @Test
    void differentMembersCannotRegisterTheSameNormalizedLoginEmail() {
        saveCredentials("010-3333-4444", "duplicate.persistence@example.com");

        var exception = assertThrows(ConstraintViolationException.class,
                () -> saveCredentials("010-3333-5555", " DUPLICATE.PERSISTENCE@EXAMPLE.COM "));

        assertEquals("uk_user_credentials_login_email", exception.getConstraintName());
    }

    @Test
    void aMemberCannotHaveTwoPasswordLoginCredentials() {
        var userId = saveCredentials("010-4444-5555", "one-login.persistence@example.com");

        var exception = assertThrows(ConstraintViolationException.class,
                () -> transactions.executeWithoutResult(status -> {
                    var user = entityManager.getReference(User.class, userId);
                    var another = UserCredentials.createFor(user, "another-login.persistence@example.com",
                            PASSWORD_HASH, PASSWORD_SET_AT);
                    entityManager.persist(another);
                    entityManager.flush();
                }));

        assertEquals("user_credentials_pkey", exception.getConstraintName());
    }

    private UUID saveCredentials(String phone, String email) {
        return transactions.execute(status -> {
            var user = User.register("홍길동", phone, PASSWORD_SET_AT);
            entityManager.persist(user);
            var credentials = UserCredentials.createFor(user, email, PASSWORD_HASH, PASSWORD_SET_AT);
            entityManager.persist(credentials);
            entityManager.flush();
            assertEquals(user.getId(), credentials.getUserId());
            return user.getId();
        });
    }
}
