package com.travelpay.user.application.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        UserRealNameService.class, UserRealNameServiceIntegrationTest.UserRealNameTestConfiguration.class
})
class UserRealNameServiceIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-31T00:00:00Z");
    private static final UUID REQUESTER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Autowired private UserRealNameService userRealNameService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecipientLookupLimiter lookupLimiter;

    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        reset(lookupLimiter);
        when(lookupLimiter.tryAcquire(any(UUID.class))).thenReturn(true);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
    }

    @Test
    void anActiveRecipientReturnsTheirIdAndRegisteredRealName() {
        var userId = saveMember(UserStatus.ACTIVE);

        var result = userRealNameService.getRecipient(REQUESTER_ID, userId);

        assertEquals(userId, result.userId());
        assertEquals("홍길동", result.realName());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void aRecipientWhoIsNotActiveIsReportedLikeAMissingOne(UserStatus state) {
        var userId = saveMember(state);

        var exception = assertThrows(BusinessException.class,
                () -> userRealNameService.getRecipient(REQUESTER_ID, userId));

        assertEquals(UserErrorCode.RECIPIENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void aMissingRecipientIsNotFound() {
        var exception = assertThrows(BusinessException.class,
                () -> userRealNameService.getRecipient(REQUESTER_ID, UUID.randomUUID()));

        assertEquals(UserErrorCode.RECIPIENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void theRequestersExhaustedAllowanceIsRejectedBeforeStartingATransaction() {
        var unavailable = mock(PlatformTransactionManager.class);
        when(unavailable.getTransaction(any())).thenThrow(new CannotCreateTransactionException("DB 연결 실패"));
        when(lookupLimiter.tryAcquire(REQUESTER_ID)).thenReturn(false);
        var service = new UserRealNameService(userRepository, unavailable, lookupLimiter);

        var exception = assertThrows(BusinessException.class,
                () -> service.getRecipient(REQUESTER_ID, UUID.randomUUID()));

        assertEquals(CommonErrorCode.TOO_MANY_REQUESTS, exception.getErrorCode());
    }

    @Test
    void anAllowanceStoreFailureIsUnavailableBeforeStartingATransaction() {
        var unavailable = mock(PlatformTransactionManager.class);
        when(unavailable.getTransaction(any())).thenThrow(new CannotCreateTransactionException("DB 연결 실패"));
        var storeFailure = new DataAccessResourceFailureException("조회 횟수 저장소 연결 실패");
        when(lookupLimiter.tryAcquire(REQUESTER_ID)).thenThrow(storeFailure);
        var service = new UserRealNameService(userRepository, unavailable, lookupLimiter);

        var exception = assertThrows(BusinessException.class,
                () -> service.getRecipient(REQUESTER_ID, UUID.randomUUID()));

        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        assertSame(storeFailure, exception.getCause());
    }

    @Test
    void aRecipientLookupWithoutARequesterIsUnauthorized() {
        var userId = saveMember(UserStatus.ACTIVE);

        var exception = assertThrows(BusinessException.class,
                () -> userRealNameService.getRecipient(null, userId));

        assertEquals(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    @Test
    void aRecipientLookupWithoutATargetIsInvalidBeforeCheckingTheAllowance() {
        when(lookupLimiter.tryAcquire(REQUESTER_ID))
                .thenThrow(new DataAccessResourceFailureException("조회 횟수 저장소 연결 실패"));

        var exception = assertThrows(BusinessException.class,
                () -> userRealNameService.getRecipient(REQUESTER_ID, null));

        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }

    @Test
    void aFundingIdentityDoesNotUseTheRecipientLookupAllowance() {
        var userId = saveMember(UserStatus.ACTIVE);
        when(lookupLimiter.tryAcquire(userId))
                .thenThrow(new DataAccessResourceFailureException("조회 횟수 저장소 연결 실패"));

        var result = userRealNameService.getFundingIdentity(userId, userId);

        assertEquals(userId, result.userId());
        assertEquals("홍길동", result.realName());
    }

    @Test
    void anActiveMemberReturnsTheirIdAndRegisteredRealNameAsTheFundingIdentity() {
        var userId = saveMember(UserStatus.ACTIVE);

        var result = userRealNameService.getFundingIdentity(userId, userId);

        assertEquals(userId, result.userId());
        assertEquals("홍길동", result.realName());
    }

    @ParameterizedTest
    @CsvSource({"SUSPENDED,ACCOUNT_SUSPENDED", "CLOSED,ACCOUNT_CLOSED"})
    void aBlockedMemberGetsTheirOwnAccountStatusInsteadOfAFundingIdentity(UserStatus state, UserErrorCode expected) {
        var userId = saveMember(state);

        var exception = assertThrows(BusinessException.class, () -> userRealNameService.getFundingIdentity(userId, userId));

        assertEquals(expected, exception.getErrorCode());
    }

    @Test
    void aMissingMemberHasNoFundingIdentity() {
        var missingId = UUID.randomUUID();
        var exception = assertThrows(BusinessException.class,
                () -> userRealNameService.getFundingIdentity(missingId, missingId));

        assertEquals(UserErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void anotherMembersFundingIdentityIsRejectedBeforeStartingATransaction() {
        var unavailable = mock(PlatformTransactionManager.class);
        when(unavailable.getTransaction(any())).thenThrow(new CannotCreateTransactionException("DB 연결 실패"));
        var service = new UserRealNameService(userRepository, unavailable, lookupLimiter);
        var requesterId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        var otherId = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");

        var exception = assertThrows(BusinessException.class,
                () -> service.getFundingIdentity(requesterId, otherId));

        assertEquals(CommonErrorCode.FORBIDDEN, exception.getErrorCode());
        verifyNoInteractions(unavailable);
    }

    @Test
    void aFundingIdentityWithoutARequesterIsUnauthorized() {
        var userId = saveMember(UserStatus.ACTIVE);

        var exception = assertThrows(BusinessException.class,
                () -> userRealNameService.getFundingIdentity(null, userId));

        assertEquals(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    @Test
    void aFundingIdentityWithoutATargetIsInvalid() {
        var requesterId = saveMember(UserStatus.ACTIVE);

        var exception = assertThrows(BusinessException.class,
                () -> userRealNameService.getFundingIdentity(requesterId, null));

        assertEquals(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }

    @Test
    void aDatabaseFailureIsServiceUnavailableRatherThanNotFound() {
        renameTable("users", "unavailable_users");
        try {
            var userId = UUID.randomUUID();
            var recipient = assertThrows(BusinessException.class,
                    () -> userRealNameService.getRecipient(REQUESTER_ID, UUID.randomUUID()));
            var fundingIdentity = assertThrows(BusinessException.class,
                    () -> userRealNameService.getFundingIdentity(userId, userId));

            assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, recipient.getErrorCode());
            assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, fundingIdentity.getErrorCode());
        }
        finally {
            renameTable("unavailable_users", "users");
        }
    }

    @Test
    void aTransactionThatCannotStartIsServiceUnavailable() {
        // DB에 연결할 수 없으면 조회 전에 트랜잭션 시작부터 실패합니다.
        var unavailable = mock(PlatformTransactionManager.class);
        when(unavailable.getTransaction(any())).thenThrow(new CannotCreateTransactionException("DB 연결 실패"));
        var service = new UserRealNameService(userRepository, unavailable, lookupLimiter);
        var userId = UUID.randomUUID();

        var recipient = assertThrows(BusinessException.class,
                () -> service.getRecipient(REQUESTER_ID, UUID.randomUUID()));
        var fundingIdentity = assertThrows(BusinessException.class,
                () -> service.getFundingIdentity(userId, userId));

        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, recipient.getErrorCode());
        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, fundingIdentity.getErrorCode());
    }

    private void renameTable(String from, String to) {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service." + from + " rename to " + to).executeUpdate());
    }

    private UUID saveMember(UserStatus state) {
        var userId = transactions.execute(status -> {
            var user = User.register("홍길동", "010-1700-0001", AT);
            entityManager.persist(user);
            return user.getId();
        });
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());
        return userId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class UserRealNameTestConfiguration {

        @Bean
        RecipientLookupLimiter recipientLookupLimiter() {
            return mock(RecipientLookupLimiter.class);
        }
    }
}
