package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class
})
class UserJpaAuditingTest {

    private static final Instant PHONE_CHECKED_AT = Instant.parse("2026-10-29T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-29T00:00:10Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-10-29T00:01:00Z");
    private static final Instant READ_AT = Instant.parse("2026-10-29T00:02:00Z");

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
        auditAt(CREATED_AT);
    }

    @Test
    void persistRecordsAuditDatesSeparatelyFromThePhoneFormatCheckTime() {
        var userId = saveUser("010-1234-5678");

        transactions.executeWithoutResult(status -> {
            var stored = entityManager.find(User.class, userId);

            assertEquals(CREATED_AT, stored.getCreatedAt());
            assertEquals(CREATED_AT, stored.getUpdatedAt());
            assertEquals(PHONE_CHECKED_AT, stored.getPhoneVerifiedAt());
        });
    }

    @Test
    void dirtyUpdateChangesOnlyTheModificationDateAndReadingDoesNotChangeIt() {
        var userId = saveUser("010-1111-2222");
        auditAt(UPDATED_AT);

        transactions.executeWithoutResult(status -> {
            var stored = entityManager.find(User.class, userId);
            // 테스트에서만 필드를 변경해 JPA의 실제 수정 이벤트를 재현합니다.
            ReflectionTestUtils.setField(stored, "realName", "김길동");
            entityManager.flush();
        });

        auditAt(READ_AT);
        transactions.executeWithoutResult(status -> {
            var stored = entityManager.find(User.class, userId);

            assertEquals("김길동", stored.getRealName());
            assertEquals(CREATED_AT, stored.getCreatedAt());
            assertEquals(UPDATED_AT, stored.getUpdatedAt());
            assertEquals(PHONE_CHECKED_AT, stored.getPhoneVerifiedAt());
        });
    }

    private UUID saveUser(String phone) {
        return transactions.execute(status -> {
            var user = User.register("홍길동", phone, PHONE_CHECKED_AT);
            entityManager.persist(user);
            entityManager.flush();
            assertNotNull(user.getId());
            return user.getId();
        });
    }

    private void auditAt(Instant time) {
        auditingHandler.setDateTimeProvider(() -> Optional.of(time));
    }

}
