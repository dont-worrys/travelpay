package com.travelpay.user.application.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.LoginMethod;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        MyInfoService.class, MyInfoServiceIntegrationTest.MyInfoTestConfiguration.class
})
class MyInfoServiceIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final String EMAIL = "my.info@example.com";
    private static final String PHONE = "010-1600-0001";
    private static final String PASSWORD_HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder()
            .encode("MyInfo-test-password!");

    @Autowired private MyInfoService myInfoService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
    }

    @Test
    void returnsTheMemberWithTheirSelfLoginEmail() {
        var userId = saveMember(true);

        var result = myInfoService.getMyInfo(userId);

        assertEquals(userId, result.userId());
        assertEquals("홍길동", result.realName());
        assertEquals(PHONE, result.phone());
        assertEquals(EMAIL, result.loginEmail());
        assertEquals(UserStatus.ACTIVE, result.status());
        assertEquals(AuthRole.USER, result.role());
        assertEquals(List.of(LoginMethod.PASSWORD), result.loginMethods());
        var storedCreatedAt = transactions.execute(status -> entityManager.find(User.class, userId).getCreatedAt());
        assertEquals(storedCreatedAt, result.createdAt());
    }

    @Test
    void aMemberWithoutSelfLoginHasNoEmailAndNoPasswordMethod() {
        var userId = saveMember(false);

        var result = myInfoService.getMyInfo(userId);

        assertNull(result.loginEmail());
        assertEquals(List.of(), result.loginMethods());
    }

    @Test
    void aDatabaseFailureIsServiceUnavailableRatherThanAnUnexpectedError() {
        renameTable("users", "unavailable_users");
        try {
            var exception = assertThrows(BusinessException.class, () -> myInfoService.getMyInfo(UUID.randomUUID()));
            assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        }
        finally {
            renameTable("unavailable_users", "users");
        }
    }

    private void renameTable(String from, String to) {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service." + from + " rename to " + to).executeUpdate());
    }

    @Test
    void aMissingMemberIsTreatedAsNotSignedIn() {
        var missing = UUID.randomUUID();

        var exception = assertThrows(BusinessException.class, () -> myInfoService.getMyInfo(missing));

        assertEquals(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    @Test
    void readsTheMemberAndTheirSelfLoginWithTwoStatements() {
        var userId = saveMember(true);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        var statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        try {
            myInfoService.getMyInfo(userId);

            assertEquals(2L, statistics.getPrepareStatementCount(), "회원과 로그인 수단을 각각 한 번씩 조회해야 합니다.");
        }
        finally {
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
    }

    private UUID saveMember(boolean withSelfLogin) {
        return transactions.execute(status -> {
            var user = User.register("홍길동", PHONE, AT);
            entityManager.persist(user);
            if (withSelfLogin) {
                entityManager.persist(UserCredentials.createFor(user, EMAIL, PASSWORD_HASH, AT));
            }
            return user.getId();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class MyInfoTestConfiguration {
    }
}
