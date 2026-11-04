package com.travelpay.user.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.user.application.dto.PasswordChangeCommand;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.common.security.ValidatingPasswordEncoder;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        CurrentMemberService.class, PasswordChangeService.class, PasswordChangeServiceIntegrationTest.TestBeans.class
})
@TestPropertySource(properties = "travelpay.user.password-change.transaction-timeout=1s")
class PasswordChangeServiceIntegrationTest {

    private static final String EMAIL = "password.member@example.com";
    private static final String OLD_PASSWORD = "OriginalPassword-2026!";
    private static final String NEW_PASSWORD = "ChangedPassword-2026!";
    private static final String CLIENT_IP = "203.0.113.10";
    private static final Instant SET_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant CHANGE_AT = SET_AT.plusSeconds(60);

    @Autowired private PasswordChangeService service;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserCredentialsRepository credentialsRepository;
    @Autowired private TrackingEncoder encoder;
    @Autowired private RecordingLimiter limiter;

    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private UUID userId;
    private String originalHash;

    @BeforeEach
    void setUp() {
        encoder.reset();
        limiter.reset();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        originalHash = encoder.delegate.encode(OLD_PASSWORD);
        userId = transactions.execute(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            var member = User.register("홍길동", "010-1700-0001", SET_AT);
            entityManager.persist(member);
            entityManager.persist(UserCredentials.createFor(member, EMAIL, originalHash, SET_AT));
            return member.getId();
        });
    }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void changesThePasswordForActiveMembersWithHashWorkOutsideTransactions(AuthRole role) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", role).setParameter("id", userId).executeUpdate());

        service.changePassword(userId, command());

        var stored = stored();
        assertNotEquals(originalHash, stored.hash());
        assertTrue(encoder.delegate.matches(NEW_PASSWORD, stored.hash()));
        assertFalse(encoder.delegate.matches(OLD_PASSWORD, stored.hash()));
        assertEquals(CHANGE_AT, stored.changedAt());
        assertEquals(List.of(false), encoder.matchTransactions);
        assertEquals(List.of(false), encoder.encodeTransactions);
        assertEquals(calls("start", "success"), limiter.calls);
        assertTrue(limiter.transactionStates.stream().noneMatch(Boolean::booleanValue));
    }

    @Test
    void aWrongCurrentPasswordIsCountedWithoutChangingStoredCredentials() {
        assertError(UserErrorCode.CURRENT_PASSWORD_MISMATCH,
                new PasswordChangeCommand("WrongPassword-2026!", NEW_PASSWORD, CLIENT_IP));

        assertOriginal();
        assertEquals(calls("start", "failure"), limiter.calls);
        assertTrue(encoder.encodeTransactions.isEmpty());
    }

    @Test
    void acceptsAnExistingPasswordThatDoesNotMeetTheNewPasswordStrengthPolicy() {
        updateHash(encoder.delegate.encode("short"));

        service.changePassword(userId, new PasswordChangeCommand("short", NEW_PASSWORD, CLIENT_IP));

        assertTrue(encoder.delegate.matches(NEW_PASSWORD, stored().hash()));
        assertEquals(CHANGE_AT, stored().changedAt());
    }

    @Test
    void rejectsTheSamePasswordAfterCorrectVerificationWithoutEncodingItAgain() {
        assertError(UserErrorCode.NEW_PASSWORD_SAME_AS_CURRENT,
                new PasswordChangeCommand(OLD_PASSWORD, OLD_PASSWORD, CLIENT_IP));

        assertOriginal();
        assertEquals(calls("start", "success"), limiter.calls);
        assertTrue(encoder.encodeTransactions.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void invalidApplicationInputIsRejectedBeforePasswordVerification(PasswordChangeCommand command) {
        assertError(CommonErrorCode.INVALID_REQUEST, command);

        assertOriginal();
        assertTrue(encoder.matchTransactions.isEmpty());
        assertTrue(encoder.encodeTransactions.isEmpty());
        assertTrue(limiter.calls.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "CLOSED"})
    void blockedMembersCannotChangeTheirPasswords(UserStatus state) {
        updateStatus(state);

        assertError(state == UserStatus.SUSPENDED ? UserErrorCode.ACCOUNT_SUSPENDED : UserErrorCode.ACCOUNT_CLOSED, command());

        assertOriginal();
        assertTrue(limiter.calls.isEmpty());
    }

    @Test
    void aMissingMemberIsUnauthenticated() {
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> service.changePassword(UUID.randomUUID(), command())).getErrorCode());
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> service.changePassword(null, command())).getErrorCode());
        assertTrue(limiter.calls.isEmpty());
    }

    @Test
    void aMemberWithoutCredentialsCannotUsePasswordChangeToCreateThem() {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "delete from UserCredentials c where c.userId = :id").setParameter("id", userId).executeUpdate());

        assertError(UserErrorCode.CREDENTIAL_CONFLICT, command());

        transactions.executeWithoutResult(status -> assertEquals(0L, entityManager.createQuery(
                "select count(c) from UserCredentials c", Long.class).getSingleResult()));
        assertTrue(limiter.calls.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{bcrypt}broken", "{noop}OriginalPassword-2026!", "{unknown}hash"})
    void unreadableStoredHashesAreSystemFailuresAndNotWrongPasswordAttempts(String hash) {
        updateHash(hash);

        assertError(CommonErrorCode.SERVICE_UNAVAILABLE, command());

        assertEquals(hash, stored().hash());
        assertEquals(calls("start", "cancel"), limiter.calls);
        assertTrue(encoder.encodeTransactions.isEmpty());
    }

    @Test
    void credentialsChangedWhileTheOldPasswordWasComparedAreNotOverwritten() {
        var competingHash = encoder.delegate.encode("ConcurrentPassword-2026!");
        encoder.onMatch = () -> updateHash(competingHash);

        assertError(UserErrorCode.CREDENTIAL_CONFLICT, command());

        assertEquals(competingHash, stored().hash());
        assertEquals(SET_AT, stored().changedAt());
    }

    @Test
    void aChangeTimestampUpdatedDuringHashingIsNotOverwritten() {
        var competingAt = SET_AT.plusSeconds(30);
        encoder.onEncode = () -> transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update UserCredentials c set c.passwordChangedAt = :at where c.userId = :id")
                .setParameter("at", competingAt).setParameter("id", userId).executeUpdate());

        assertError(UserErrorCode.CREDENTIAL_CONFLICT, command());

        assertEquals(originalHash, stored().hash());
        assertEquals(competingAt, stored().changedAt());
    }

    @Test
    void aMemberSuspendedDuringHashingCannotCommitTheChange() {
        encoder.onEncode = () -> updateStatus(UserStatus.SUSPENDED);

        assertError(UserErrorCode.ACCOUNT_SUSPENDED, command());

        assertOriginal();
    }

    @ParameterizedTest
    @ValueSource(strings = {"credentials", "member"})
    void deletedCredentialsOrMembersAreNotRecreatedDuringTheChange(String removed) {
        encoder.onEncode = () -> transactions.executeWithoutResult(status -> {
            entityManager.createQuery("delete from UserCredentials c where c.userId = :id")
                    .setParameter("id", userId).executeUpdate();
            if (removed.equals("member")) {
                entityManager.createQuery("delete from User u where u.id = :id")
                        .setParameter("id", userId).executeUpdate();
            }
        });

        assertError(removed.equals("member") ? CommonErrorCode.UNAUTHORIZED : UserErrorCode.CREDENTIAL_CONFLICT, command());

        transactions.executeWithoutResult(status -> assertEquals(0L, entityManager.createQuery(
                "select count(c) from UserCredentials c", Long.class).getSingleResult()));
    }

    @Test
    void aDatabaseReadFailureIsUnavailableAndDoesNotStartPasswordVerification() {
        sql("alter table user_service.user_credentials rename to unavailable_credentials");
        try {
            assertError(CommonErrorCode.SERVICE_UNAVAILABLE, command());
        } finally {
            sql("alter table user_service.unavailable_credentials rename to user_credentials");
        }
        assertOriginal();
        assertTrue(encoder.matchTransactions.isEmpty());
        assertTrue(limiter.calls.isEmpty());
    }

    @Test
    void aFailureAtDatabaseCommitRollsBackTheChangedPassword() {
        sql("""
                create function user_service.reject_password_commit() returns trigger language plpgsql as $$
                begin
                    raise exception 'forced commit failure' using errcode = '40001';
                end $$
                """, """
                create constraint trigger reject_password_commit
                after update on user_service.user_credentials deferrable initially deferred
                for each row execute function user_service.reject_password_commit()
                """);
        try {
            assertError(CommonErrorCode.SERVICE_UNAVAILABLE, command());
        } finally {
            sql("drop trigger if exists reject_password_commit on user_service.user_credentials",
                    "drop function if exists user_service.reject_password_commit()");
        }
        assertOriginal();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "failure", "success"})
    void attemptStorageFailuresStopBeforeThePasswordIsWritten(String failingCall) {
        limiter.failingCall = failingCall;
        var current = failingCall.equals("failure") ? "WrongPassword-2026!" : OLD_PASSWORD;

        assertError(CommonErrorCode.SERVICE_UNAVAILABLE, new PasswordChangeCommand(current, NEW_PASSWORD, CLIENT_IP));

        assertOriginal();
        assertTrue(encoder.encodeTransactions.isEmpty());
    }

    @Test
    void aBlockedAttemptDoesNotRunPasswordComparisonOrHashing() {
        limiter.allowed = false;

        assertError(CommonErrorCode.TOO_MANY_REQUESTS, command());

        assertOriginal();
        assertTrue(encoder.matchTransactions.isEmpty());
        assertTrue(encoder.encodeTransactions.isEmpty());
        assertEquals(calls("start"), limiter.calls);
    }

    @Test
    void anAmbientTransactionIsRejectedBeforeHashWork() {
        assertThrows(IllegalTransactionStateException.class,
                () -> transactions.executeWithoutResult(status -> service.changePassword(userId, command())));
        assertOriginal();
        assertTrue(encoder.matchTransactions.isEmpty());
    }

    @Test
    void twoChangesFromTheSameCredentialsHaveOneWinner() throws Exception {
        encoder.beforeEncode = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(NEW_PASSWORD));
            var second = executor.submit(() -> attempt("AnotherPassword-2026!"));
            var attempts = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(1L, attempts.stream().filter(a -> a.error() == null).count());
            assertEquals(1L, attempts.stream().filter(a -> a.error() == UserErrorCode.CREDENTIAL_CONFLICT).count());
            var winner = attempts.stream().filter(a -> a.error() == null).findFirst().orElseThrow();
            assertTrue(encoder.delegate.matches(winner.password(), stored().hash()));
            assertEquals(CHANGE_AT, stored().changedAt());
        }
    }

    @Test
    void databaseLockWaitingFailsBeforeTheOtherTransactionReleasesItsLock() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                credentialsRepository.findByUserIdForUpdate(userId).orElseThrow();
                locked.countDown();
                awaitRelease(release);
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var changing = executor.submit(() -> attempt(NEW_PASSWORD));
                assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, changing.get(8, TimeUnit.SECONDS).error());
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertOriginal();
    }

    private BusinessException assertError(ErrorCode expected, PasswordChangeCommand command) {
        var exception = assertThrows(BusinessException.class, () -> service.changePassword(userId, command));
        assertEquals(expected, exception.getErrorCode());
        return exception;
    }

    private ChangeAttempt attempt(String next) {
        try {
            service.changePassword(userId, new PasswordChangeCommand(OLD_PASSWORD, next, CLIENT_IP));
            return new ChangeAttempt(next, null);
        } catch (BusinessException exception) {
            return new ChangeAttempt(next, exception.getErrorCode());
        }
    }

    private StoredPassword stored() {
        return transactions.execute(status -> {
            var credentials = entityManager.find(UserCredentials.class, userId);
            return new StoredPassword(credentials.getPasswordHash(), credentials.getPasswordChangedAt());
        });
    }

    private void assertOriginal() {
        assertEquals(new StoredPassword(originalHash, SET_AT), stored());
    }

    private void updateHash(String hash) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update UserCredentials c set c.passwordHash = :hash where c.userId = :id")
                .setParameter("hash", hash).setParameter("id", userId).executeUpdate());
    }

    private void updateStatus(UserStatus state) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());
    }

    private void sql(String... statements) {
        transactions.executeWithoutResult(status -> {
            for (var statement : statements) entityManager.createNativeQuery(statement).executeUpdate();
        });
    }

    private static PasswordChangeCommand command() {
        return new PasswordChangeCommand(OLD_PASSWORD, NEW_PASSWORD, CLIENT_IP);
    }

    private static List<String> calls(String... methods) {
        return Stream.of(methods).map(m -> m + " " + EMAIL + " " + CLIENT_IP).toList();
    }

    private static Stream<PasswordChangeCommand> invalidCommands() {
        return Stream.of(
                null,
                new PasswordChangeCommand(null, NEW_PASSWORD, CLIENT_IP),
                new PasswordChangeCommand("a".repeat(73), NEW_PASSWORD, CLIENT_IP),
                new PasswordChangeCommand(OLD_PASSWORD, null, CLIENT_IP),
                new PasswordChangeCommand(OLD_PASSWORD, "short", CLIENT_IP),
                new PasswordChangeCommand(OLD_PASSWORD, "a".repeat(71) + "1!", CLIENT_IP),
                new PasswordChangeCommand(OLD_PASSWORD, NEW_PASSWORD, null),
                new PasswordChangeCommand(OLD_PASSWORD, NEW_PASSWORD, " "));
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Lock test timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record StoredPassword(String hash, Instant changedAt) { }
    private record ChangeAttempt(String password, ErrorCode error) { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestBeans {
        @Bean TrackingEncoder passwordEncoder() { return new TrackingEncoder(); }
        @Bean RecordingLimiter attemptLimiter() { return new RecordingLimiter(); }
        @Bean Clock clock() { return Clock.fixed(CHANGE_AT, ZoneOffset.UTC); }
        @Bean LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }
    }

    static class RecordingLimiter implements LoginAttemptLimiter {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> transactionStates = new CopyOnWriteArrayList<>();
        boolean allowed = true;
        String failingCall;

        void reset() { calls.clear(); transactionStates.clear(); allowed = true; failingCall = null; }
        @Override public boolean tryStart(String email, String ip) { record("start", email, ip); return allowed; }
        @Override public void recordFailure(String email, String ip) { record("failure", email, ip); }
        @Override public void recordSuccess(String email, String ip) { record("success", email, ip); }
        @Override public void cancel(String email, String ip) { record("cancel", email, ip); }

        private void record(String method, String email, String ip) {
            calls.add(method + " " + email + " " + ip);
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (method.equals(failingCall)) throw new QueryTimeoutException("Attempt store timeout");
        }
    }

    static class TrackingEncoder implements PasswordEncoder {
        final PasswordEncoder delegate = new ValidatingPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        final List<Boolean> matchTransactions = new CopyOnWriteArrayList<>();
        final List<Boolean> encodeTransactions = new CopyOnWriteArrayList<>();
        Runnable onMatch;
        Runnable onEncode;
        CyclicBarrier beforeEncode;

        void reset() { matchTransactions.clear(); encodeTransactions.clear(); onMatch = null; onEncode = null; beforeEncode = null; }

        @Override public String encode(CharSequence raw) {
            encodeTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (beforeEncode != null) {
                try { beforeEncode.await(10, TimeUnit.SECONDS); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }
            var action = onEncode;
            onEncode = null;
            if (action != null) action.run();
            return delegate.encode(raw);
        }

        @Override public boolean matches(CharSequence raw, String hash) {
            matchTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            var action = onMatch;
            onMatch = null;
            if (action != null) action.run();
            return delegate.matches(raw, hash);
        }
    }
}
