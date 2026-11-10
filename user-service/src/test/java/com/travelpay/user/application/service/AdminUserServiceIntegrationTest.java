package com.travelpay.user.application.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.domain.repository.UserSearchCondition;
import com.travelpay.user.domain.repository.UserSearchItem;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import com.travelpay.user.infrastructure.persistence.UserRepositoryImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 관리자 정지·해제·세션 종료를 실제 PostgreSQL의 행 잠금과 트랜잭션으로 확인합니다. */
@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        AdminUserService.class, AdminUserServiceIntegrationTest.TestBeans.class
})
@TestPropertySource(properties = "travelpay.user.admin.transaction-timeout=2s")
class AdminUserServiceIntegrationTest {

    private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired private AdminUserService service;
    @Autowired private PausingUserRepository users;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private UUID firstMaster;
    private UUID secondMaster;
    private UUID member;

    @BeforeEach
    void setUp() {
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
        firstMaster = member("010-3000-0001", AuthRole.MASTER);
        secondMaster = member("010-3000-0002", AuthRole.MASTER);
        member = member("010-3000-0003", AuthRole.USER);
    }

    @Test
    void suspendingChangesTheStatusAndIncreasesTheSessionVersion() {
        assertEquals(UserStatus.SUSPENDED, service.changeStatus(firstMaster, member, UserStatus.SUSPENDED));

        assertEquals(UserStatus.SUSPENDED, status(member));
        assertEquals(1L, version(member));
    }

    // 이 API 밖(직접 SQL 등)에서 정지된 회원도, 해제하면 예전 세션이 다시 쓰이지 않아야 함
    @Test
    void reactivatingEndsTheOldSessionsEvenWhenTheSuspensionHappenedElsewhere() {
        setStatus(member, UserStatus.SUSPENDED);

        assertEquals(UserStatus.ACTIVE, service.changeStatus(firstMaster, member, UserStatus.ACTIVE));

        assertEquals(UserStatus.ACTIVE, status(member));
        assertEquals(1L, version(member));
    }

    @Test
    void requestingTheCurrentStatusChangesNothing() {
        assertEquals(UserStatus.ACTIVE, service.changeStatus(firstMaster, member, UserStatus.ACTIVE));

        assertEquals(0L, version(member));
    }

    @Test
    void aMasterCannotSuspendItselfButMayRequestItsCurrentActiveStatus() {
        assertError(UserErrorCode.MASTER_PROTECTION_CONFLICT,
                () -> service.changeStatus(firstMaster, firstMaster, UserStatus.SUSPENDED));
        assertEquals(UserStatus.ACTIVE, service.changeStatus(firstMaster, firstMaster, UserStatus.ACTIVE));

        assertEquals(UserStatus.ACTIVE, status(firstMaster));
        assertEquals(0L, version(firstMaster));
    }

    @Test
    void aClosedMemberKeepsItsStatus() {
        setStatus(member, UserStatus.CLOSED);

        assertError(UserErrorCode.CLOSED_ACCOUNT_UNCHANGEABLE,
                () -> service.changeStatus(firstMaster, member, UserStatus.ACTIVE));

        assertEquals(UserStatus.CLOSED, status(member));
    }

    @Test
    void anUnknownTargetIsNotFound() {
        assertError(UserErrorCode.NOT_FOUND,
                () -> service.changeStatus(firstMaster, UUID.randomUUID(), UserStatus.SUSPENDED));
    }

    @Test
    void anActorThatIsNoLongerAnActiveMasterIsRejectedWithoutChanges() {
        setStatus(firstMaster, UserStatus.SUSPENDED);
        assertError(UserErrorCode.ACCOUNT_SUSPENDED,
                () -> service.changeStatus(firstMaster, member, UserStatus.SUSPENDED));

        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", AuthRole.USER).setParameter("id", secondMaster).executeUpdate());
        assertError(CommonErrorCode.FORBIDDEN,
                () -> service.changeStatus(secondMaster, member, UserStatus.SUSPENDED));

        assertEquals(UserStatus.ACTIVE, status(member));
        assertEquals(0L, version(member));
    }

    @Test
    void twoMastersSuspendingEachOtherAtTheSameTimeLeaveOneActiveMaster() throws Exception {
        var pause = users.pauseNextLock();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(firstMaster, secondMaster));
            assertTrue(pause.locked().await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> attempt(secondMaster, firstMaster));
            try {
                awaitAnotherTransactionWaitingForALock();
            } finally {
                pause.release().countDown();
            }

            assertEquals(new Attempt(UserStatus.SUSPENDED, null), first.get(10, TimeUnit.SECONDS));
            assertEquals(new Attempt(null, UserErrorCode.ACCOUNT_SUSPENDED), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(UserStatus.ACTIVE, status(firstMaster));
        assertEquals(UserStatus.SUSPENDED, status(secondMaster));
    }

    @Test
    void waitingForALockedMemberGivesUpAtTheTransactionTimeout() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                users.findAllByIdForUpdate(List.of(member));
                locked.countDown();
                await(release);
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var changing = executor.submit(() -> attempt(firstMaster, member));
                assertEquals(new Attempt(null, CommonErrorCode.SERVICE_UNAVAILABLE), changing.get(8, TimeUnit.SECONDS));
            }
            finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertEquals(UserStatus.ACTIVE, status(member));
        assertEquals(0L, version(member));
    }

    @Test
    void endingSessionsIncreasesOnlyTheTargetVersionAndReportsAnUnknownMember() {
        service.endSessions(firstMaster, member);

        assertEquals(1L, version(member));
        assertEquals(0L, version(firstMaster));
        assertError(UserErrorCode.NOT_FOUND, () -> service.endSessions(firstMaster, UUID.randomUUID()));
    }

    @Test
    void onlyACommittedStatusChangeIsAudited() {
        service.changeStatus(firstMaster, member, UserStatus.SUSPENDED);
        service.changeStatus(firstMaster, member, UserStatus.SUSPENDED);
        assertError(UserErrorCode.NOT_FOUND,
                () -> service.changeStatus(firstMaster, UUID.randomUUID(), UserStatus.SUSPENDED));

        assertEquals(List.of(Map.of("event", "ADMIN_USER_STATUS_CHANGED", "actorId", firstMaster.toString(),
                "targetUserId", member.toString(), "fromStatus", "ACTIVE", "toStatus", "SUSPENDED")), auditEvents());
    }

    @Test
    void endingSessionsIsAuditedOnlyForAnExistingMember() {
        service.endSessions(firstMaster, member);
        assertError(UserErrorCode.NOT_FOUND, () -> service.endSessions(firstMaster, UUID.randomUUID()));

        assertEquals(List.of(Map.of("event", "ADMIN_USER_SESSIONS_ENDED", "actorId", firstMaster.toString(),
                "targetUserId", member.toString())), auditEvents());
    }

    @BeforeEach
    void captureAuditLog() {
        audit.start();
        ((Logger) LoggerFactory.getLogger("travelpay.audit.admin")).addAppender(audit);
    }

    @AfterEach
    void releaseAuditLog() {
        ((Logger) LoggerFactory.getLogger("travelpay.audit.admin")).detachAppender(audit);
    }

    private List<Map<String, Object>> auditEvents() {
        return audit.list.stream().map(event -> event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value))).toList();
    }

    private UUID member(String phone, AuthRole role) {
        var userId = transactions.execute(status -> {
            var user = User.register("홍길동", phone, AT);
            entityManager.persist(user);
            return user.getId();
        });
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.role = :role where u.id = :id")
                .setParameter("role", role).setParameter("id", userId).executeUpdate());
        return userId;
    }

    private void setStatus(UUID userId, UserStatus state) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.status = :state where u.id = :id")
                .setParameter("state", state).setParameter("id", userId).executeUpdate());
    }

    private UserStatus status(UUID userId) {
        return transactions.execute(status -> entityManager.find(User.class, userId).getStatus());
    }

    private long version(UUID userId) {
        return transactions.execute(status -> entityManager.find(User.class, userId).getSessionVersion());
    }

    private Attempt attempt(UUID actorId, UUID targetId) {
        try {
            return new Attempt(service.changeStatus(actorId, targetId, UserStatus.SUSPENDED), null);
        }
        catch (BusinessException exception) {
            return new Attempt(null, exception.getErrorCode());
        }
    }

    private static void assertError(ErrorCode expected, Runnable call) {
        assertEquals(expected, assertThrows(BusinessException.class, call::run).getErrorCode());
    }

    /** 두 번째 요청이 첫 번째 요청의 행 잠금을 기다리기 시작했는지 PostgreSQL에서 확인합니다. 잠금이 없으면 그대로 진행합니다. */
    private void awaitAnotherTransactionWaitingForALock() throws InterruptedException {
        for (var attempt = 0; attempt < 50; attempt++) {
            var waiting = transactions.execute(status -> ((Number) entityManager.createNativeQuery(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'")
                    .getSingleResult()).longValue());
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 시험이 제한 시간 안에 끝나지 않았습니다.");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Attempt(UserStatus status, ErrorCode error) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        PausingUserRepository pausingUserRepository(UserRepositoryImpl delegate) {
            return new PausingUserRepository(delegate);
        }
    }

    /** 다음 잠금 조회 한 번을 행을 잠근 상태로 멈춰, 다른 요청이 그 잠금을 기다리게 만듭니다. */
    static final class PausingUserRepository implements UserRepository {

        private final UserRepository delegate;
        private final AtomicReference<Pause> nextPause = new AtomicReference<>();

        PausingUserRepository(UserRepository delegate) {
            this.delegate = delegate;
        }

        Pause pauseNextLock() {
            var pause = new Pause(new CountDownLatch(1), new CountDownLatch(1));
            nextPause.set(pause);
            return pause;
        }

        @Override
        public List<User> findAllByIdForUpdate(Collection<UUID> userIds) {
            var locked = delegate.findAllByIdForUpdate(userIds);
            var pause = nextPause.getAndSet(null);
            if (pause != null) {
                pause.locked().countDown();
                await(pause.release());
            }
            return locked;
        }

        @Override public User save(User user) { return delegate.save(user); }
        @Override public Optional<User> findById(UUID userId) { return delegate.findById(userId); }
        @Override public boolean existsByPhone(String phone) { return delegate.existsByPhone(phone); }
        @Override public boolean increaseSessionVersion(UUID userId) { return delegate.increaseSessionVersion(userId); }
        @Override public List<UserSearchItem> search(UserSearchCondition condition, int offset, int limit) {
            return delegate.search(condition, offset, limit);
        }
    }

    record Pause(CountDownLatch locked, CountDownLatch release) {
    }
}
