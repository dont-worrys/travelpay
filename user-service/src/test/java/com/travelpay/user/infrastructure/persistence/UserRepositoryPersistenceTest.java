package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class,
        JpaRepositoryTestConfiguration.class
})
class UserRepositoryPersistenceTest {

    private static final Instant PHONE_CHECKED_AT = Instant.parse("2026-10-29T00:00:00Z");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void savesUserAndFindsStoredValuesInAnotherTransaction() {
        var userId = saveUser("홍길동", "010-1200-0001");
        assertNotNull(userId);

        transactions.executeWithoutResult(status -> {
            var stored = userRepository.findById(userId).orElseThrow();

            assertEquals(userId, stored.getId());
            assertEquals("홍길동", stored.getRealName());
            assertEquals("010-1200-0001", stored.getPhone());
            assertEquals(PHONE_CHECKED_AT, stored.getPhoneVerifiedAt());
            assertEquals(UserStatus.ACTIVE, stored.getStatus());
            assertEquals(AuthRole.USER, stored.getRole());
            assertNotNull(stored.getCreatedAt());
            assertNotNull(stored.getUpdatedAt());
        });
    }

    @Test
    void returnsEmptyWhenUserDoesNotExist() {
        var missingId = UUID.fromString("9d990303-a3ce-4a3b-ae4f-68c2161199d1");

        var found = transactions.execute(status -> userRepository.findById(missingId));

        assertTrue(found.isEmpty());
    }

    @Test
    void recognizesRegisteredPhone() {
        saveUser("김길동", "010-1200-0002");

        var exists = transactions.execute(status -> userRepository.existsByPhone("010-1200-0002"));

        assertTrue(exists);
    }

    @Test
    void returnsFalseForUnregisteredPhone() {
        var exists = transactions.execute(status -> userRepository.existsByPhone("010-1200-0099"));

        assertFalse(exists);
    }

    @Test
    void rejectsDuplicatePhoneWithoutChangingExistingUser() {
        var originalId = saveUser("홍길동", "010-1200-0003");

        var exception = assertThrows(BusinessException.class,
                () -> saveUser("김길동", "010-1200-0003"));

        assertEquals(UserErrorCode.PHONE_ALREADY_EXISTS, exception.getErrorCode());
        assertTrue(exception.getCause() instanceof DataIntegrityViolationException);
        transactions.executeWithoutResult(status -> {
            var stored = userRepository.findById(originalId).orElseThrow();
            assertEquals("홍길동", stored.getRealName());
        });
    }

    @Test
    void rollsBackSaveWhenTheEnclosingTransactionFails() {
        var user = User.register("박길동", "010-1200-0004", PHONE_CHECKED_AT);

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            userRepository.save(user);
            throw new IllegalStateException("후속 처리 실패로 롤백합니다.");
        }));

        assertNotNull(user.getId());
        transactions.executeWithoutResult(status -> {
            assertTrue(userRepository.findById(user.getId()).isEmpty());
            assertFalse(userRepository.existsByPhone("010-1200-0004"));
        });
    }

    private UUID saveUser(String realName, String phone) {
        return transactions.execute(status -> userRepository.save(
                User.register(realName, phone, PHONE_CHECKED_AT)).getId());
    }

}
