package com.travelpay.user.application.dto;

import java.time.Instant;

import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class,
        JpaRepositoryTestConfiguration.class
})
class SignupResultPersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void returnsTheStoredMemberIdAndStatusAsValuesOutsideTheTransaction() {
        var transactions = new TransactionTemplate(transactionManager);
        var result = transactions.execute(status -> {
            var savedUser = userRepository.save(User.register(
                    "홍길동", "010-1290-0001", Instant.parse("2026-10-29T00:00:00Z")));
            return SignupResult.from(savedUser);
        });

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertNotNull(result);
        assertNotNull(result.userId());
        assertEquals(UserStatus.ACTIVE, result.status());
        transactions.executeWithoutResult(status -> {
            var stored = userRepository.findById(result.userId()).orElseThrow();
            assertEquals("010-1290-0001", stored.getPhone());
        });
    }
}
