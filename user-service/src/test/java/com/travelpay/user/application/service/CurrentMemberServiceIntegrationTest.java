package com.travelpay.user.application.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 세션 요청의 회원 확인이 실제 PostgreSQL 값으로 상태와 세션 버전을 어떤 순서로 판단하는지 확인합니다. */
@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        CurrentMemberService.class
})
class CurrentMemberServiceIntegrationTest {

    @Autowired private CurrentMemberService service;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private UUID userId;

    @BeforeEach
    void setUp() {
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        userId = transactions.execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var user = User.register("홍길동", "010-1600-0001", Instant.parse("2026-11-01T00:00:00Z"));
            entityManager.persist(user);
            return user.getId();
        });
    }

    @Test
    void aSessionCarryingTheCurrentVersionPasses() {
        assertEquals(new AuthenticatedUser(userId, AuthRole.USER), service.requireActiveSession(userId, 0L));
    }

    @Test
    void aSessionCarryingAnOlderVersionMustLogInAgain() {
        increaseSessionVersion();

        assertError(CommonErrorCode.UNAUTHORIZED, () -> service.requireActiveSession(userId, 0L));
        assertEquals(new AuthenticatedUser(userId, AuthRole.USER), service.requireActiveSession(userId, 1L));
    }

    @ParameterizedTest
    @CsvSource({"SUSPENDED, USER_ACCOUNT_SUSPENDED", "CLOSED, USER_ACCOUNT_CLOSED"})
    void anInactiveMemberIsReportedBeforeAnOutdatedVersion(UserStatus inactive, String expectedCode) {
        increaseSessionVersion();
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :status where u.id = :id")
                .setParameter("status", inactive).setParameter("id", userId).executeUpdate());

        var exception = assertThrows(BusinessException.class, () -> service.requireActiveSession(userId, 0L));

        assertEquals(expectedCode, exception.getErrorCode().getCode());
    }

    @Test
    void aMemberThatNoLongerExistsMustLogInAgain() {
        assertError(CommonErrorCode.UNAUTHORIZED, () -> service.requireActiveSession(UUID.randomUUID(), 0L));
    }

    @Test
    void theStatusOnlyCheckIgnoresTheSessionVersion() {
        increaseSessionVersion();

        assertEquals(new AuthenticatedUser(userId, AuthRole.USER), service.requireActive(userId));
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :status where u.id = :id")
                .setParameter("status", UserStatus.SUSPENDED).setParameter("id", userId).executeUpdate());
        assertError(UserErrorCode.ACCOUNT_SUSPENDED, () -> service.requireActive(userId));
    }

    private void increaseSessionVersion() {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.sessionVersion = u.sessionVersion + 1 where u.id = :id")
                .setParameter("id", userId).executeUpdate());
    }

    private static void assertError(ErrorCode expected, Runnable call) {
        assertEquals(expected, assertThrows(BusinessException.class, call::run).getErrorCode());
    }
}
