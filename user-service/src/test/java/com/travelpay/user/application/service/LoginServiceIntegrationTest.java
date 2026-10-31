package com.travelpay.user.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.user.application.dto.LoginCommand;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.common.security.ValidatingPasswordEncoder;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        LoginService.class, LoginServiceIntegrationTest.LoginTestConfiguration.class
})
class LoginServiceIntegrationTest {

    private static final String EMAIL = "login.member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final Instant AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final String CLIENT_IP = "203.0.113.10";

    @Autowired private LoginService loginService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TrackingPasswordEncoder encoder;
    @Autowired private RecordingAttemptLimiter limiter;

    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private UUID userId;

    @BeforeEach
    void setUp() {
        encoder.reset();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        userId = transactions.execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var user = User.register("홍길동", "010-1500-0001", AT);
            entityManager.persist(user);
            entityManager.persist(UserCredentials.createFor(user, EMAIL, encoder.encode(PASSWORD), AT));
            return user.getId();
        });
        encoder.reset();
        limiter.reset();
    }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void verifiesThePasswordOutsideDatabaseTransactionsAndReturnsTheCurrentMember(AuthRole role) {
        updateRole(role);

        var result = loginService.login(new LoginCommand("  LOGIN.MEMBER@EXAMPLE.COM  ", PASSWORD, CLIENT_IP));

        assertNotNull(result);
        assertEquals(userId, result.userId());
        assertEquals(role, result.role());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(List.of(false), encoder.matchTransactions);
        transactions.executeWithoutResult(status -> {
            var credentials = entityManager.find(UserCredentials.class, userId);
            assertEquals(AT, credentials.getPasswordChangedAt());
            assertEquals(1L, entityManager.createQuery("select count(u) from User u", Long.class).getSingleResult());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown@example.com", EMAIL})
    void missingEmailAndWrongPasswordUseTheSameErrorAndStillPerformAHashComparison(String email) {
        assertError(UserErrorCode.INVALID_CREDENTIALS, new LoginCommand(email, "WrongPassword-2026!", CLIENT_IP));

        assertEquals(List.of(false), encoder.matchTransactions);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void wrongPasswordsDoNotRevealTheMembersAccountState(UserStatus state) {
        updateStatus(state);

        assertError(UserErrorCode.INVALID_CREDENTIALS, new LoginCommand(EMAIL, "WrongPassword-2026!", CLIENT_IP));
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void correctPasswordsAreRejectedWhenTheCurrentMemberCannotLogIn(UserStatus state) {
        updateStatus(state);

        assertError(state == UserStatus.SUSPENDED ? UserErrorCode.ACCOUNT_SUSPENDED : UserErrorCode.ACCOUNT_CLOSED,
                new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
    }

    @Test
    void aPasswordChangedDuringComparisonCannotCreateALoginWithTheOldPassword() {
        encoder.onMatch = () -> updateHash(encoder.encode("ChangedPassword-2026!"));

        assertError(UserErrorCode.INVALID_CREDENTIALS, new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
    }

    @Test
    void aMemberSuspendedDuringComparisonCannotLogIn() {
        encoder.onMatch = () -> updateStatus(UserStatus.SUSPENDED);

        assertError(UserErrorCode.ACCOUNT_SUSPENDED, new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
    }

    @Test
    void usesTheRoleAfterPasswordComparisonRatherThanAnEarlierSnapshot() {
        encoder.onMatch = () -> updateRole(AuthRole.MASTER);

        assertEquals(AuthRole.MASTER, loginService.login(new LoginCommand(EMAIL, PASSWORD, CLIENT_IP)).role());
    }

    @Test
    void credentialsDeletedDuringComparisonCannotCreateALogin() {
        encoder.onMatch = () -> transactions.executeWithoutResult(status ->
                entityManager.createQuery("delete from UserCredentials").executeUpdate());

        assertError(UserErrorCode.INVALID_CREDENTIALS, new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
    }

    @Test
    void rejectsAnExtraSuffixBeyondTheBcryptLimitEvenForApplicationCalls() {
        var maximumPassword = "a".repeat(70) + "1!";
        updateHash(encoder.encode(maximumPassword));

        assertError(UserErrorCode.INVALID_CREDENTIALS, new LoginCommand(EMAIL, maximumPassword + "x", CLIENT_IP));
    }

    @Test
    void doesNotReapplyTheNewPasswordCreationPolicyToExistingCredentials() {
        updateHash(encoder.encode("short"));

        assertEquals(userId, loginService.login(new LoginCommand(EMAIL, "short", CLIENT_IP)).userId());
    }

    @Test
    void passwordComparisonRemainsCaseSensitive() {
        assertError(UserErrorCode.INVALID_CREDENTIALS,
                new LoginCommand(EMAIL, PASSWORD.toLowerCase(java.util.Locale.ROOT), CLIENT_IP));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{unsupported-password-encoder}stored-hash", "{bcrypt}broken", "{bcrypt}",
            "{bcrypt}$2a$10$truncated-bcrypt-hash", "{noop}TravelpayDemo-2026!"})
    void anUnreadableStoredHashIsAnAuthenticationSystemFailure(String hash) {
        updateHash(hash);

        assertError(CommonErrorCode.SERVICE_UNAVAILABLE, new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
    }

    @Test
    void aRealDatabaseReadFailureIsServiceUnavailableRatherThanInvalidCredentials() {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service.user_credentials rename to unavailable_credentials").executeUpdate());
        try {
            assertError(CommonErrorCode.SERVICE_UNAVAILABLE, new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
        }
        finally {
            transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                    "alter table user_service.unavailable_credentials rename to user_credentials").executeUpdate());
        }
    }

    @ParameterizedTest
    @CsvSource({"'  LOGIN.MEMBER@EXAMPLE.COM  ', " + EMAIL, "'  Unknown@Example.COM ', unknown@example.com"})
    void wrongPasswordsAndUnknownEmailsAreRecordedAsFailuresUnderTheNormalizedEmail(String input, String key) {
        assertError(UserErrorCode.INVALID_CREDENTIALS, new LoginCommand(input, "WrongPassword-2026!", CLIENT_IP));

        assertEquals(List.of("start " + key + " " + CLIENT_IP, "failure " + key + " " + CLIENT_IP), limiter.calls);
    }

    @Test
    void aSuccessfulLoginIsRecordedAsSuccess() {
        loginService.login(new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));

        assertEquals(calls("start", "success"), limiter.calls);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void correctPasswordsForMembersWhoCannotLogInAreNotCountedAsFailures(UserStatus state) {
        updateStatus(state);

        assertThrows(BusinessException.class, () -> loginService.login(new LoginCommand(EMAIL, PASSWORD, CLIENT_IP)));
        assertEquals(calls("start", "cancel"), limiter.calls);
    }

    @Test
    void systemFailuresAreNotCountedAsFailures() {
        updateHash("{bcrypt}broken");

        assertError(CommonErrorCode.SERVICE_UNAVAILABLE, new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
        assertEquals(calls("start", "cancel"), limiter.calls);
    }

    @Test
    void aBlockedAttemptIs429BeforeThePasswordIsComparedAndIsNotCountedAgain() {
        limiter.allowed = false;

        assertError(CommonErrorCode.TOO_MANY_REQUESTS, new LoginCommand(EMAIL, PASSWORD, CLIENT_IP));
        assertEquals(List.of(), encoder.matchTransactions);
        assertEquals(calls("start"), limiter.calls);
    }

    @ParameterizedTest
    @CsvSource({"start, " + PASSWORD, "failure, WrongPassword-2026!", "success, " + PASSWORD})
    void anAttemptCountThatCannotBeCheckedOrRecordedIs503RatherThanALoginResult(String failingCall, String password) {
        limiter.failingCall = failingCall;

        assertError(CommonErrorCode.SERVICE_UNAVAILABLE, new LoginCommand(EMAIL, password, CLIENT_IP));
        assertEquals(failingCall, limiter.calls.getLast().split(" ")[0]);
        if (failingCall.equals("start")) {
            assertEquals(List.of(), encoder.matchTransactions);
        }
    }

    @Test
    void refusesAnAmbientDatabaseTransactionBeforePasswordComparison() {
        assertThrows(IllegalTransactionStateException.class,
                () -> transactions.execute(status -> loginService.login(new LoginCommand(EMAIL, PASSWORD, CLIENT_IP))));
        assertEquals(List.of(), encoder.matchTransactions);
    }

    private static List<String> calls(String... names) {
        return Arrays.stream(names).map(name -> name + " " + EMAIL + " " + CLIENT_IP).toList();
    }

    private void assertError(ErrorCode code, LoginCommand command) {
        assertEquals(code, assertThrows(BusinessException.class, () -> loginService.login(command)).getErrorCode());
    }

    private void updateStatus(UserStatus state) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());
    }

    private void updateRole(AuthRole role) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", role).setParameter("id", userId).executeUpdate());
    }

    private void updateHash(String hash) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update UserCredentials c set c.passwordHash = :hash where c.userId = :id")
                .setParameter("hash", hash).setParameter("id", userId).executeUpdate());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class LoginTestConfiguration {
        @Bean TrackingPasswordEncoder passwordEncoder() { return new TrackingPasswordEncoder(); }
        @Bean RecordingAttemptLimiter attemptLimiter() { return new RecordingAttemptLimiter(); }
    }

    /** LoginService가 결과마다 어떤 횟수 처리를 요청하는지 기록합니다. Redis 동작은 RedisLoginAttemptLimiterTest가 확인합니다. */
    static class RecordingAttemptLimiter implements LoginAttemptLimiter {
        private final List<String> calls = new ArrayList<>();
        private boolean allowed;
        private String failingCall;

        void reset() { calls.clear(); allowed = true; failingCall = null; }

        @Override
        public boolean tryStart(String email, String clientIp) {
            record("start", email, clientIp);
            return allowed;
        }

        @Override public void recordFailure(String email, String clientIp) { record("failure", email, clientIp); }
        @Override public void recordSuccess(String email, String clientIp) { record("success", email, clientIp); }
        @Override public void cancel(String email, String clientIp) { record("cancel", email, clientIp); }

        private void record(String call, String email, String clientIp) {
            calls.add(call + " " + email + " " + clientIp);
            if (call.equals(failingCall)) { throw new QueryTimeoutException("시도 횟수 저장소 응답 지연"); }
        }
    }

    static class TrackingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate =
                new ValidatingPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        private final List<Boolean> matchTransactions = new ArrayList<>();
        private Runnable onMatch;

        void reset() { matchTransactions.clear(); onMatch = null; }

        @Override public String encode(CharSequence rawPassword) { return delegate.encode(rawPassword); }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            var action = onMatch;
            onMatch = null;
            if (action != null) { action.run(); }
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
