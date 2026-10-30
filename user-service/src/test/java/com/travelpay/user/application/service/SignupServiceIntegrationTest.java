package com.travelpay.user.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.user.application.dto.SignupCommand;
import com.travelpay.user.application.dto.SignupResult;
import com.travelpay.user.common.config.ClockConfiguration;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserConsent;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserConsentRepository;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.infrastructure.persistence.UserConsentRepositoryImpl;
import com.travelpay.user.infrastructure.persistence.UserCredentialsRepositoryImpl;
import com.travelpay.user.infrastructure.persistence.UserRepositoryImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, ClockConfiguration.class,
        PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        SignupService.class, SignupServiceIntegrationTest.SignupTestConfiguration.class
})
class SignupServiceIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final TermsDocumentId SERVICE = new TermsDocumentId("SERVICE_TERMS", "v1");
    private static final TermsDocumentId PRIVACY = new TermsDocumentId("PRIVACY_TERMS", "v1");
    private static final TermsDocumentId MARKETING = new TermsDocumentId("MARKETING_TERMS", "v1");

    @Autowired
    private SignupService signupService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private TestState state;
    @Autowired
    private TrackingPasswordEncoder encoder;

    private EntityManager entityManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        state.reset();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        // 이 클래스 전용 PostgreSQL 컨텍스트의 데이터를 FK 역순으로 정리합니다.
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
            publish(SERVICE, true, AT.minusSeconds(60));
            publish(PRIVACY, true, AT.minusSeconds(60));
            publish(MARKETING, false, AT.minusSeconds(60));
            publish(new TermsDocumentId("SERVICE_TERMS", "v0"), true, AT.minusSeconds(120));
            publish(new TermsDocumentId("SERVICE_TERMS", "v2"), true, AT.plusSeconds(300));
        });
    }

    @Test
    void commitsTheMemberHashedCredentialsAndAllSubmittedConsentsTogether() {
        var command = command("  Member@Example.COM  ", "010-1500-0001",
                List.of(SERVICE, PRIVACY, MARKETING));

        var result = signupService.signup(command);

        assertNotNull(result);
        assertNotNull(result.userId());
        assertEquals(UserStatus.ACTIVE, result.status());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1, state.encodings.get());
        assertRowCounts(1, 1, 3);
        transactions.executeWithoutResult(status -> {
            var user = entityManager.find(User.class, result.userId());
            var credentials = entityManager.find(UserCredentials.class, result.userId());
            assertEquals("홍길동", user.getRealName());
            assertEquals("010-1500-0001", user.getPhone());
            assertEquals(AuthRole.USER, user.getRole());
            assertEquals(AT, user.getPhoneVerifiedAt());
            assertEquals("member@example.com", credentials.getLoginEmail());
            assertEquals(AT, credentials.getPasswordChangedAt());
            assertNotEquals(PASSWORD, credentials.getPasswordHash());
            assertTrue(encoder.matches(PASSWORD, credentials.getPasswordHash()));
            var consents = entityManager.createQuery("select c from UserConsent c", UserConsent.class)
                    .getResultList();
            assertTrue(consents.stream().allMatch(c -> c.getAgreedAt().equals(AT)));
        });
    }

    @Test
    void permitsOmittingOptionalConsents() {
        signupService.signup(command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)));

        assertRowCounts(1, 1, 2);
    }

    @Test
    void rejectsRegistrationWhenNoTermsHaveTakenEffect() {
        transactions.executeWithoutResult(status -> {
            entityManager.createQuery("delete from TermsDocument").executeUpdate();
            publish(SERVICE, true, AT.plusSeconds(1));
        });

        var failure = assertThrows(BusinessException.class, () -> signupService.signup(
                command("member@example.com", "010-1500-0001", List.of())));

        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
        assertEquals(0, state.encodings.get());
        assertRowCounts(0, 0, 0);
    }

    @Test
    void acceptsEmptyConsentsWhenOnlyOptionalTermsExist() {
        transactions.executeWithoutResult(status -> {
            entityManager.createQuery("delete from TermsDocument").executeUpdate();
            publish(MARKETING, false, AT.minusSeconds(1));
        });

        signupService.signup(command("member@example.com", "010-1500-0001", List.of()));

        assertRowCounts(1, 1, 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConsents")
    void rejectsInvalidConsentsBeforeHashingOrWriting(String description, List<TermsDocumentId> consents) {
        var failure = assertThrows(BusinessException.class,
                () -> signupService.signup(command("member@example.com", "010-1500-0001", consents)));

        assertEquals(UserErrorCode.TERMS_CONSENT_INVALID, failure.getErrorCode());
        assertEquals(0, state.encodings.get());
        assertRowCounts(0, 0, 0);
    }

    @Test
    void rejectsANormalizedDuplicateEmailBeforeHashing() {
        signupService.signup(command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)));
        state.encodings.set(0);

        var failure = assertThrows(BusinessException.class, () -> signupService.signup(
                command("  MEMBER@EXAMPLE.COM  ", "010-1500-0002", List.of(SERVICE, PRIVACY))));

        assertEquals(UserErrorCode.EMAIL_ALREADY_EXISTS, failure.getErrorCode());
        assertEquals(0, state.encodings.get());
        assertRowCounts(1, 1, 2);
    }

    @Test
    void rejectsADuplicatePhoneBeforeHashing() {
        signupService.signup(command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)));
        state.encodings.set(0);

        var failure = assertThrows(BusinessException.class, () -> signupService.signup(
                command("another@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY))));

        assertEquals(UserErrorCode.PHONE_ALREADY_EXISTS, failure.getErrorCode());
        assertEquals(0, state.encodings.get());
        assertRowCounts(1, 1, 2);
    }

    @Test
    void doesNotWriteAnythingWhenHashingFails() {
        var failure = new IllegalStateException("테스트 해시 생성 실패");
        state.duringEncoding = () -> { throw failure; };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> signupService.signup(
                command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)))));
        assertRowCounts(0, 0, 0);
    }

    @Test
    void rollsBackAlreadyFlushedRowsAndPreservesAnUnrelatedPersistenceFailure() {
        var failure = new DataIntegrityViolationException("테스트 동의 저장 실패");
        state.consentFailure = failure;

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> signupService.signup(
                command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)))));
        assertRowCounts(0, 0, 0);
    }

    @Test
    void aDatabaseFailureBeforeHashingIsServiceUnavailableAndWritesNothing() {
        renameTable("user_credentials", "unavailable_credentials");
        try {
            var failure = assertThrows(BusinessException.class, () -> signupService.signup(
                    command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY))));
            assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
        }
        finally {
            renameTable("unavailable_credentials", "user_credentials");
        }
        assertEquals(0, state.encodings.get());
        assertRowCounts(0, 0, 0);
    }

    @Test
    void aDatabaseFailureWhileWritingIsServiceUnavailableAndRollsBackEveryRow() {
        renameTable("user_consents", "unavailable_consents");
        try {
            var failure = assertThrows(BusinessException.class, () -> signupService.signup(
                    command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY))));
            assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
        }
        finally {
            renameTable("unavailable_consents", "user_consents");
        }
        assertEquals(1, state.encodings.get());
        assertRowCounts(0, 0, 0);
    }

    private void renameTable(String from, String to) {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "alter table user_service." + from + " rename to " + to).executeUpdate());
    }

    @Test
    void rechecksTheCurrentTermsAfterHashing() {
        state.duringEncoding = () -> state.now.set(AT.plusSeconds(301));

        var failure = assertThrows(BusinessException.class, () -> signupService.signup(
                command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY))));

        assertEquals(UserErrorCode.TERMS_CONSENT_INVALID, failure.getErrorCode());
        assertEquals(1, state.encodings.get());
        assertRowCounts(0, 0, 0);
    }

    @Test
    void usesTheServerWriteTimeForAllBusinessTimestamps() {
        var writeAt = AT.plusSeconds(10);
        state.duringEncoding = () -> state.now.set(writeAt);

        var result = signupService.signup(
                command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)));

        transactions.executeWithoutResult(status -> {
            assertEquals(writeAt, entityManager.find(User.class, result.userId()).getPhoneVerifiedAt());
            assertEquals(writeAt, entityManager.find(UserCredentials.class, result.userId()).getPasswordChangedAt());
            assertTrue(entityManager.createQuery("select c from UserConsent c", UserConsent.class)
                    .getResultStream().allMatch(c -> c.getAgreedAt().equals(writeAt)));
        });
    }

    @Test
    void rejectsAnEnclosingTransactionSoHashingCannotJoinIt() {
        assertThrows(IllegalTransactionStateException.class, () -> transactions.executeWithoutResult(status ->
                signupService.signup(command("member@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)))));

        assertEquals(0, state.encodings.get());
        assertRowCounts(0, 0, 0);
    }

    @Test
    void concurrentDuplicateEmailCreatesOnlyOneCompleteRegistration() throws Exception {
        state.race = Race.EMAIL;
        state.writeBarrier = new CyclicBarrier(2);

        var attempts = race(
                command("same@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)),
                command("same@example.com", "010-1500-0002", List.of(SERVICE, PRIVACY)));

        assertSingleWinner(attempts, UserErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void concurrentDuplicatePhoneCreatesOnlyOneCompleteRegistration() throws Exception {
        state.race = Race.PHONE;
        state.writeBarrier = new CyclicBarrier(2);

        var attempts = race(
                command("first@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)),
                command("second@example.com", "010-1500-0001", List.of(SERVICE, PRIVACY)));

        assertSingleWinner(attempts, UserErrorCode.PHONE_ALREADY_EXISTS);
    }

    private List<Attempt> race(SignupCommand first, SignupCommand second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> attempt(first));
            var b = executor.submit(() -> attempt(second));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Attempt attempt(SignupCommand command) {
        try {
            return new Attempt(signupService.signup(command), null);
        } catch (BusinessException failure) {
            return new Attempt(null, failure.getErrorCode());
        }
    }

    private void assertSingleWinner(List<Attempt> attempts, UserErrorCode duplicateCode) {
        assertEquals(1, attempts.stream().filter(a -> a.result() != null).count());
        assertEquals(1, attempts.stream().filter(a -> a.error() == duplicateCode).count());
        assertRowCounts(1, 1, 2);
    }

    private void assertRowCounts(long users, long credentials, long consents) {
        transactions.executeWithoutResult(status -> {
            assertEquals(users, count("User"));
            assertEquals(credentials, count("UserCredentials"));
            assertEquals(consents, count("UserConsent"));
        });
    }

    private long count(String entity) {
        return entityManager.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
    }

    private void publish(TermsDocumentId id, boolean required, Instant effectiveAt) {
        entityManager.persist(TermsDocument.publish(id, "약관", "약관 본문", required, effectiveAt));
    }

    private SignupCommand command(String email, String phone, List<TermsDocumentId> consents) {
        return new SignupCommand(email, PASSWORD, "홍길동", phone, consents);
    }

    private static Stream<Arguments> invalidConsents() {
        return Stream.of(
                Arguments.of("빈 동의 목록", List.of()),
                Arguments.of("필수 동의 누락", List.of(SERVICE)),
                Arguments.of("중복 동의", List.of(SERVICE, PRIVACY, SERVICE)),
                Arguments.of("현재가 아닌 이전 버전", List.of(new TermsDocumentId("SERVICE_TERMS", "v0"), PRIVACY)),
                Arguments.of("아직 시행되지 않은 버전", List.of(new TermsDocumentId("SERVICE_TERMS", "v2"), PRIVACY)),
                Arguments.of("알 수 없는 약관", List.of(SERVICE, PRIVACY, new TermsDocumentId("UNKNOWN", "v1"))));
    }

    private record Attempt(SignupResult result, ErrorCode error) {
    }

    private enum Race { NONE, EMAIL, PHONE }

    static class TestState {
        final AtomicReference<Instant> now = new AtomicReference<>(AT);
        final AtomicInteger encodings = new AtomicInteger();
        volatile Runnable duringEncoding = () -> {};
        volatile DataIntegrityViolationException consentFailure;
        volatile Race race = Race.NONE;
        volatile CyclicBarrier writeBarrier;

        void reset() {
            now.set(AT);
            encodings.set(0);
            duringEncoding = () -> {};
            consentFailure = null;
            race = Race.NONE;
            writeBarrier = null;
        }

        void synchronizeWrite(Race point) {
            if (race != point || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                return;
            }
            try {
                writeBarrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("테스트 경합 대기 중단", failure);
            } catch (Exception failure) {
                throw new IllegalStateException("테스트 경합 동기화 실패", failure);
            }
        }
    }

    static class TrackingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        private final TestState state;

        TrackingPasswordEncoder(TestState state) { this.state = state; }

        @Override
        public String encode(CharSequence rawPassword) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "비밀번호 해시 중에는 DB 트랜잭션이 없어야 합니다.");
            state.encodings.incrementAndGet();
            state.duringEncoding.run();
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class SignupTestConfiguration {
        @Bean
        TestState state() { return new TestState(); }

        @Bean
        @Primary
        Clock signupClock(TestState state) {
            return clockAtZone(state, ZoneOffset.UTC);
        }

        private Clock clockAtZone(TestState state, ZoneId zone) {
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId targetZone) { return clockAtZone(state, targetZone); }
                @Override public Instant instant() { return state.now.get(); }
            };
        }

        @Bean
        TrackingPasswordEncoder passwordEncoder(TestState state) { return new TrackingPasswordEncoder(state); }

        @Bean
        @Primary
        UserRepository userRepository(UserRepositoryImpl delegate, TestState state) {
            return new UserRepository() {
                @Override public User save(User user) { return delegate.save(user); }
                @Override public Optional<User> findById(UUID id) { return delegate.findById(id); }
                @Override public boolean existsByPhone(String phone) {
                    var exists = delegate.existsByPhone(phone);
                    state.synchronizeWrite(Race.PHONE);
                    return exists;
                }
            };
        }

        @Bean
        @Primary
        UserCredentialsRepository credentialsRepository(UserCredentialsRepositoryImpl delegate, TestState state) {
            return new UserCredentialsRepository() {
                @Override public UserCredentials save(UserCredentials value) { return delegate.save(value); }
                @Override public Optional<UserCredentials> findByLoginEmail(String email) {
                    return delegate.findByLoginEmail(email);
                }
                @Override public Optional<UserCredentials> findByUserId(UUID userId) {
                    return delegate.findByUserId(userId);
                }
                @Override public boolean existsByLoginEmail(String email) {
                    var exists = delegate.existsByLoginEmail(email);
                    state.synchronizeWrite(Race.EMAIL);
                    return exists;
                }
            };
        }

        @Bean
        @Primary
        UserConsentRepository consentRepository(UserConsentRepositoryImpl delegate, TestState state,
                EntityManagerFactory factory) {
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
            return consents -> {
                var stored = delegate.saveAll(consents);
                if (state.consentFailure != null) {
                    entityManager.flush();
                    throw state.consentFailure;
                }
                return stored;
            };
        }
    }
}
