package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.domain.repository.UserSearchCondition;
import com.travelpay.user.domain.repository.UserSearchItem;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class,
        PostgresJpaTestConfiguration.class,
        JpaRepositoryTestConfiguration.class
})
class UserRepositoryPersistenceTest {

    private static final Instant PHONE_CHECKED_AT = Instant.parse("2026-10-29T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialsRepository credentialsRepository;

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

    @Test
    void increasesTheSessionVersionByOneAndReportsAMissingMember() {
        var userId = saveUser("최길동", "010-1200-0101");

        assertEquals(Boolean.TRUE, transactions.execute(status -> userRepository.increaseSessionVersion(userId)));
        assertEquals(Boolean.TRUE, transactions.execute(status -> userRepository.increaseSessionVersion(userId)));
        assertEquals(Boolean.FALSE,
                transactions.execute(status -> userRepository.increaseSessionVersion(UUID.randomUUID())));

        assertEquals(2L, sessionVersion(userId));
    }

    @Test
    void locksTheRequestedMembersInIdOrderAndSkipsMissingIds() {
        var first = saveUser("정길동", "010-1200-0102");
        var second = saveUser("강길동", "010-1200-0103");

        var locked = transactions.execute(status -> userRepository
                .findAllByIdForUpdate(List.of(second, UUID.randomUUID(), first)).stream().map(User::getId).toList());

        assertEquals(Stream.of(first, second).sorted(Comparator.comparing(UUID::toString)).toList(), locked);
    }

    @Test
    void savingAMemberReadEarlierDoesNotUndoASessionVersionIncreasedMeanwhile() {
        var userId = saveUser("조길동", "010-1200-0104");
        var separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactions.executeWithoutResult(status -> {
            var stale = userRepository.findById(userId).orElseThrow();
            // 이 트랜잭션이 회원을 읽은 뒤, 다른 트랜잭션이 세션 버전을 올리고 먼저 커밋합니다.
            separate.executeWithoutResult(other -> userRepository.increaseSessionVersion(userId));
            stale.changeStatus(UserStatus.SUSPENDED);
            userRepository.save(stale);
        });

        assertEquals(1L, sessionVersion(userId));
        transactions.executeWithoutResult(status ->
                assertEquals(UserStatus.SUSPENDED, userRepository.findById(userId).orElseThrow().getStatus()));
    }

    @Test
    void searchAppliesEachGivenConditionAsAnExactMatch() {
        clearMembers();
        var active = saveMemberWithEmail("010-1300-0001", "active@example.com", UserStatus.ACTIVE, AT);
        var suspended = saveMemberWithEmail("010-1300-0002", "suspended@example.com", UserStatus.SUSPENDED, AT);
        saveUser("홍길동", "010-1300-0003");

        assertEquals(List.of(active), searchIds(new UserSearchCondition(active, null, null, null)));
        assertEquals(List.of(active), searchIds(new UserSearchCondition(null, "active@example.com", null, null)));
        assertEquals(List.of(), searchIds(new UserSearchCondition(null, "active@example", null, null)));
        assertEquals(List.of(suspended), searchIds(new UserSearchCondition(null, null, "010-1300-0002", null)));
        assertEquals(List.of(suspended), searchIds(new UserSearchCondition(null, null, null, UserStatus.SUSPENDED)));
        assertEquals(List.of(), searchIds(new UserSearchCondition(null, null, "010-1300-0002", UserStatus.ACTIVE)));
    }

    @Test
    void searchIncludesMembersWithoutPasswordLoginAndListsTheNewestFirst() {
        clearMembers();
        var oldest = saveMemberWithEmail("010-1300-0011", "oldest@example.com", UserStatus.ACTIVE, AT);
        var middle = saveMemberWithEmail("010-1300-0012", "middle@example.com", UserStatus.ACTIVE, AT.plusSeconds(60));
        var newest = saveUser("홍길동", "010-1300-0013");
        setCreatedAt(newest, AT.plusSeconds(120));

        var found = transactions.execute(status ->
                userRepository.search(new UserSearchCondition(null, null, null, null), 0, 10));

        assertEquals(List.of(newest, middle, oldest), found.stream().map(UserSearchItem::userId).toList());
        assertNull(found.getFirst().loginEmail());
        assertEquals("middle@example.com", found.get(1).loginEmail());
    }

    @Test
    void membersCreatedAtTheSameMomentAreListedByIdDescending() {
        clearMembers();
        var first = saveMemberWithEmail("010-1300-0021", "first@example.com", UserStatus.ACTIVE, AT);
        var second = saveMemberWithEmail("010-1300-0022", "second@example.com", UserStatus.ACTIVE, AT);

        var expected = Stream.of(first, second).sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        assertEquals(expected, searchIds(new UserSearchCondition(null, null, null, null)));
    }

    @Test
    void searchReturnsOnlyTheRequestedSlice() {
        clearMembers();
        var ids = new ArrayList<UUID>();
        for (var number = 0; number < 5; number++) {
            ids.add(saveMemberWithEmail("010-1300-003" + number, "slice" + number + "@example.com",
                    UserStatus.ACTIVE, AT.plusSeconds(number)));
        }

        var found = transactions.execute(status ->
                userRepository.search(new UserSearchCondition(null, null, null, null), 2, 2));

        assertEquals(List.of(ids.get(2), ids.get(1)), found.stream().map(UserSearchItem::userId).toList());
    }

    private List<UUID> searchIds(UserSearchCondition condition) {
        return transactions.execute(status ->
                userRepository.search(condition, 0, 10).stream().map(UserSearchItem::userId).toList());
    }

    private UUID saveMemberWithEmail(String phone, String email, UserStatus memberStatus, Instant createdAt) {
        var userId = saveUser("홍길동", phone);
        transactions.executeWithoutResult(status -> {
            var user = userRepository.findById(userId).orElseThrow();
            credentialsRepository.save(UserCredentials.createFor(user, email, "{bcrypt}unused-test-hash", AT));
            user.changeStatus(memberStatus);
        });
        setCreatedAt(userId, createdAt);
        return userId;
    }

    private void setCreatedAt(UUID userId, Instant createdAt) {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update User u set u.createdAt = :at where u.id = :id")
                .setParameter("at", createdAt).setParameter("id", userId).executeUpdate());
    }

    // 같은 DB를 다른 시험과 함께 쓰므로, 검색 시험은 회원을 비우고 시작합니다.
    private void clearMembers() {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
    }

    private long sessionVersion(UUID userId) {
        return transactions.execute(status -> userRepository.findById(userId).orElseThrow().getSessionVersion());
    }

    private UUID saveUser(String realName, String phone) {
        return transactions.execute(status -> userRepository.save(
                User.register(realName, phone, PHONE_CHECKED_AT)).getId());
    }

}
