package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
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
class UserCredentialsRepositoryPersistenceTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-10-29T00:00:00Z");
    private static final String PASSWORD_HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder()
            .encode("Repository-test-password!");

    @Autowired
    private UserCredentialsRepository credentialsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void savesCredentialsWithTheMemberIdAndFindsStoredValuesInAnotherTransaction() {
        var userId = saveCredentials("  Repository.Member@Example.COM  ", "010-1300-0001");
        assertNotNull(userId);

        transactions.executeWithoutResult(status -> {
            var stored = credentialsRepository.findByLoginEmail("repository.member@example.com").orElseThrow();

            assertEquals(userId, stored.getUserId());
            assertEquals(userId, stored.getUser().getId());
            assertEquals("repository.member@example.com", stored.getLoginEmail());
            assertEquals(PASSWORD_HASH, stored.getPasswordHash());
            assertEquals(REGISTERED_AT, stored.getPasswordChangedAt());
            assertNotNull(stored.getCreatedAt());
        });
    }

    @Test
    void loginLookupReadsMemberStateAndRoleWithOneStatement() {
        var userId = saveCredentials("repository.fetch@example.com", "010-1300-0002");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        var statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        try {
            // 저장 트랜잭션을 끝낸 뒤 새 영속성 컨텍스트에서 조회합니다.
            transactions.executeWithoutResult(status -> {
                var stored = credentialsRepository.findByLoginEmail("repository.fetch@example.com").orElseThrow();
                var member = stored.getUser();

                assertEquals(userId, member.getId());
                assertEquals(UserStatus.ACTIVE, member.getStatus());
                assertEquals(AuthRole.USER, member.getRole());
                assertEquals("홍길동", member.getRealName());
            });

            assertEquals(1L, statistics.getPrepareStatementCount(),
                    "로그인 정보와 회원을 한 번의 SQL로 조회해야 합니다.");
        } finally {
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
    }

    @Test
    void returnsEmptyForAnUnregisteredLoginEmail() {
        var found = transactions.execute(status ->
                credentialsRepository.findByLoginEmail("repository.missing@example.com"));

        assertTrue(found.isEmpty());
    }

    @Test
    void findsTheCredentialsOfAMemberById() {
        var userId = saveCredentials("repository.by-member@example.com", "010-1300-0008");

        var loginEmail = transactions.execute(status ->
                credentialsRepository.findByUserId(userId).orElseThrow().getLoginEmail());

        assertEquals("repository.by-member@example.com", loginEmail);
    }

    @Test
    void returnsEmptyForAMemberWithoutCredentials() {
        var userId = transactions.execute(status ->
                userRepository.save(User.register("홍길동", "010-1300-0009", REGISTERED_AT)).getId());

        var found = transactions.execute(status -> credentialsRepository.findByUserId(userId));

        assertTrue(found.isEmpty());
    }

    @Test
    void recognizesARegisteredLoginEmail() {
        saveCredentials("repository.exists@example.com", "010-1300-0003");

        var exists = transactions.execute(status ->
                credentialsRepository.existsByLoginEmail("repository.exists@example.com"));

        assertTrue(exists);
    }

    @Test
    void returnsFalseForAnUnregisteredLoginEmail() {
        var exists = transactions.execute(status ->
                credentialsRepository.existsByLoginEmail("repository.not-registered@example.com"));

        assertFalse(exists);
    }

    @Test
    void rejectsDuplicateLoginEmailAndRollsBackTheNewMember() {
        var originalId = saveCredentials("Repository.Duplicate@Example.COM", "010-1300-0004");

        var exception = assertThrows(BusinessException.class,
                () -> saveCredentials("repository.duplicate@example.com", "010-1300-0005"));

        assertEquals(UserErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        assertTrue(exception.getCause() instanceof DataIntegrityViolationException);
        transactions.executeWithoutResult(status -> {
            var stored = credentialsRepository.findByLoginEmail("repository.duplicate@example.com").orElseThrow();
            assertEquals(originalId, stored.getUserId());
            assertFalse(userRepository.existsByPhone("010-1300-0005"));
        });
    }

    @Test
    void cannotReplaceExistingCredentialsBySavingAnotherLoginForTheSameMember() {
        var userId = saveCredentials("repository.original@example.com", "010-1300-0006");

        var exception = assertThrows(DataIntegrityViolationException.class,
                () -> transactions.executeWithoutResult(status -> {
                    var user = userRepository.findById(userId).orElseThrow();
                    credentialsRepository.save(UserCredentials.createFor(user,
                            "repository.replacement@example.com", PASSWORD_HASH, REGISTERED_AT));
                }));

        assertTrue(exception.getMostSpecificCause().getMessage().contains("user_credentials_pkey"));
        transactions.executeWithoutResult(status -> {
            var original = credentialsRepository.findByLoginEmail("repository.original@example.com").orElseThrow();
            assertEquals(userId, original.getUserId());
            assertFalse(credentialsRepository.existsByLoginEmail("repository.replacement@example.com"));
        });
    }

    @Test
    void rollsBackMemberAndCredentialsWhenTheEnclosingTransactionFails() {
        var user = User.register("박길동", "010-1300-0007", REGISTERED_AT);

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            var storedUser = userRepository.save(user);
            credentialsRepository.save(UserCredentials.createFor(storedUser,
                    "repository.rollback@example.com", PASSWORD_HASH, REGISTERED_AT));
            throw new IllegalStateException("후속 처리 실패로 롤백합니다.");
        }));

        assertNotNull(user.getId());
        transactions.executeWithoutResult(status -> {
            assertTrue(userRepository.findById(user.getId()).isEmpty());
            assertFalse(credentialsRepository.existsByLoginEmail("repository.rollback@example.com"));
        });
    }

    private UUID saveCredentials(String loginEmail, String phone) {
        return transactions.execute(status -> {
            var user = userRepository.save(User.register("홍길동", phone, REGISTERED_AT));
            var credentials = UserCredentials.createFor(user, loginEmail, PASSWORD_HASH, REGISTERED_AT);
            return credentialsRepository.save(credentials).getUserId();
        });
    }

}
