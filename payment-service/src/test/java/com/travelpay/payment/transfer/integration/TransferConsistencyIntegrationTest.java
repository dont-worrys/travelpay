package com.travelpay.payment.transfer.integration;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi;
import com.travelpay.payment.infra.client.user.UserFeignApi;
import com.travelpay.payment.infra.client.wallet.WalletFeignApi;
import com.travelpay.payment.support.PostgresContainerSupport;
import com.travelpay.payment.transfer.application.TransferAdminService;
import com.travelpay.payment.transfer.application.TransferReviewReconciler;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.client.TransferLedgerReader;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.domain.TransferStatusHistoryRepository;
import com.travelpay.payment.transfer.dto.AdminTransferResolveRequest;
import com.travelpay.payment.transfer.dto.AdminTransferResponse;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 송금 정합성을 실제 스레드 동시 실행과 실제 PostgreSQL(커밋되는 트랜잭션)로 확인합니다.
 * 다른 통합 테스트와 달리 테스트 트랜잭션으로 감싸지 않아(동시 요청끼리 서로의 커밋을 봐야 함) 만든 데이터는 테스트마다 지웁니다.
 *
 * <p>원장 Feign 호출은 원장 계약(같은 reference는 한 번만 처리하고 처음 결과를 돌려줌, 잔액 부족이면 차감·증가 모두 없이 422,
 * 계정 잠금으로 잔액 변경이 직렬화됨)을 흉내 내는 테스트용 원장({@link FakeLedger})으로 바꿉니다.
 * 실제 원장의 잠금·멱등성은 2026-11-02 로컬 E2E(동시 송금 20건, reference 재전송)로 확인된 사실을 전제로 하며,
 * 이 테스트는 송금 서비스가 동시 상황에서 원장에 중복 요청을 보내지 않고 원장 결과와 같은 상태를 남기는지를 봅니다.</p>
 *
 * <p>검증하는 불변식: ① COMPLETED 송금마다 원장 POSTED 1건 ② FAILED 송금은 원장 반영 없음
 * ③ 송금인·멱등성 키당 송금 1건 ④ 잔액 합계 보존·음수 없음·지갑별 잔액 = 시작 잔액 − 완료 송금 출금 + 완료 송금 입금
 * ⑤ 복구 후 PROCESSING 0건 ⑥ 상태와 사유 컬럼 조합(FAILED ↔ 실패 사유, MANUAL_REVIEW ↔ 검토 사유)</p>
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "transfer.recovery.enabled=false",
        "payment.settlement-recovery.enabled=false"
})
class TransferConsistencyIntegrationTest extends PostgresContainerSupport {

    private static final String LEDGER_URL = "http://ledger-service/internal/v1/ledger/transactions";
    private static final int THREADS = 20;

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferAdminService transferAdminService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferStatusHistoryRepository historyRepository;

    @Autowired
    private TransferLedgerReader ledgerReader;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private LedgerFeignApi ledgerFeignApi;

    @MockitoBean
    private WalletFeignApi walletFeignApi;

    @MockitoBean
    private UserFeignApi userFeignApi;

    private final Map<UUID, UUID> walletOf = new HashMap<>();
    private final Set<UUID> users = new HashSet<>();
    private FakeLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new FakeLedger();
        doAnswer(invocation -> ledger.register(invocation.getArgument(0))).when(ledgerFeignApi).register(any());
    }

    @AfterEach
    void cleanUp() {
        for (UUID user : users) {
            jdbcTemplate.update("""
                    delete from transfer_status_history
                    where transfer_id in (select id from transfer where sender_id = ? or receiver_id = ?)""", user, user);
            jdbcTemplate.update("delete from transfer where sender_id = ? or receiver_id = ?", user, user);
        }
    }

    /**
     * 같은 송금인·같은 키·같은 내용의 요청 20건이 동시에 와도 송금은 1건, 원장 요청은 1번입니다.
     * UNIQUE(sender_id, idempotency_key)에 걸린 요청은 먼저 저장된 송금으로 응답하며 오류가 나지 않습니다.
     */
    @Test
    void sameKeyConcurrentRequestsCreateOneTransferAndCallLedgerOnce() throws Exception {
        UUID sender = member(10_000L);
        UUID receiver = member(0L);
        ledger.delayMillis = 200;

        List<Object> results = runConcurrently(THREADS, i ->
                transferService.request(sender, "same-key", new TransferCreateRequest(receiver, 3_000L, "KRW", "동시")));

        Set<UUID> transferIds = new HashSet<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(TransferResponse.class);
            TransferResponse response = (TransferResponse) result;
            transferIds.add(response.transferId());
            assertThat(response.status()).isIn(TransferStatus.PROCESSING, TransferStatus.COMPLETED);
        }
        assertEquals(1, transferIds.size());
        assertEquals(1, ledger.registerCalls.get());
        assertEquals("COMPLETED", statusOf(transferIds.iterator().next()));
        assertEquals(7_000L, ledger.balance(walletOf.get(sender)));
        assertEquals(3_000L, ledger.balance(walletOf.get(receiver)));
        assertInvariants(Map.of(sender, 10_000L, receiver, 0L));
    }

    /** 잔액을 넘는 동시 송금(10,000원에서 1,000원 × 20건): 10건만 COMPLETED, 나머지는 FAILED(잔액 부족)이고 잔액은 음수가 되지 않습니다. */
    @Test
    void concurrentTransfersOverBalanceNeverOverdraw() throws Exception {
        UUID sender = member(10_000L);
        UUID receiver = member(0L);

        List<Object> results = runConcurrently(THREADS, i ->
                transferService.request(sender, "over-" + i, new TransferCreateRequest(receiver, 1_000L, "KRW", null)));

        long completed = results.stream().filter(TransferResponse.class::isInstance).count();
        long insufficient = results.stream()
                .filter(result -> result instanceof BusinessException exception
                        && exception.getErrorCode() == TransferErrorCode.INSUFFICIENT_BALANCE)
                .count();
        assertEquals(10, completed);
        assertEquals(10, insufficient);
        assertEquals(0L, ledger.balance(walletOf.get(sender)));
        assertEquals(10_000L, ledger.balance(walletOf.get(receiver)));
        assertInvariants(Map.of(sender, 10_000L, receiver, 0L));
    }

    /** 양방향 동시 송금(A→B, B→A 각 10건): 결과와 상관없이 두 지갑 잔액 합계가 보존되고 지갑별 잔액이 완료 송금과 맞습니다. */
    @Test
    void bidirectionalConcurrentTransfersPreserveTotalBalance() throws Exception {
        UUID a = member(10_000L);
        UUID b = member(5_000L);

        runConcurrently(THREADS, i -> i % 2 == 0
                ? transferService.request(a, "ab-" + i, new TransferCreateRequest(b, 1_500L, "KRW", null))
                : transferService.request(b, "ba-" + i, new TransferCreateRequest(a, 1_500L, "KRW", null)));

        assertEquals(15_000L, ledger.balance(walletOf.get(a)) + ledger.balance(walletOf.get(b)));
        assertInvariants(Map.of(a, 10_000L, b, 5_000L));
    }

    /**
     * 응답 유실로 PROCESSING인 송금에 복구 인스턴스 4개와 같은 키 재요청 4건이 동시에 몰려도
     * 원장 재전송은 1번(선점 UPDATE)이고 재요청은 원장을 호출하지 않으며, 잔액은 한 번만 바뀝니다.
     */
    @Test
    void recoveryInstancesAndClientRetriesSettleProcessingTransferOnce() throws Exception {
        UUID sender = member(10_000L);
        UUID receiver = member(0L);
        TransferCreateRequest request = new TransferCreateRequest(receiver, 2_000L, "KRW", null);
        ledger.loseFirstResponse = true;
        UUID transferId = transferService.request(sender, "lost", request).transferId();
        assertEquals("PROCESSING", statusOf(transferId));
        jdbcTemplate.update("update transfer set updated_at = updated_at - interval '1 minute' where id = ?", transferId);
        ledger.delayMillis = 200;

        List<Object> results = runConcurrently(8, i -> i < 4
                ? transferService.recoverDue(Instant.now(), 10)
                : transferService.request(sender, "lost", request));

        int settled = results.stream().filter(Integer.class::isInstance).mapToInt(Integer.class::cast).sum();
        assertEquals(1, settled);
        assertEquals(2, ledger.registerCalls.get());
        assertEquals(1, ledger.applied(transferId));
        assertEquals("COMPLETED", statusOf(transferId));
        assertEquals(1, attemptCountOf(transferId));
        assertInvariants(Map.of(sender, 10_000L, receiver, 0L));
    }

    /**
     * 서버 2대가 같은 주기에 복구를 돌리면 서로 다른 송금을 나눠 가져갑니다(SKIP LOCKED 선점).
     * 처리 중 송금 20건이 한 주기(서버마다 10건)에 모두 확정되고, 송금마다 원장 재전송은 1번입니다.
     */
    @Test
    void twoRecoveryInstancesSplitDueTransfersInOneCycle() throws Exception {
        assumeTrue(processingCount() == 0, "DB에 다른 PROCESSING 송금이 없어야 합니다.");
        UUID sender = member(20_000L);
        UUID receiver = member(0L);
        Instant base = Instant.now().minusSeconds(3_600);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(dueProcessingTransfer(sender, receiver, "split-" + i, base.plusSeconds(i)));
        }
        ledger.delayMillis = 100;

        List<Object> results = runConcurrently(2, i -> transferService.recoverDue(Instant.now(), 10));

        assertEquals(List.of(10, 10), results);
        assertEquals(20, ledger.registerCalls.get());
        ids.forEach(id -> assertEquals("COMPLETED", statusOf(id), "status " + id));
        assertInvariants(Map.of(sender, 20_000L, receiver, 0L));
    }

    /**
     * 서버 A가 3건을 일괄 선점하고 첫 송금의 원장 호출이 길어지는 동안, 서버 B가 선점 시각 +31초 시점에 복구를 돌려
     * 대기 시간이 지난 나머지 2건을 다시 선점해 처리합니다. A는 호출 직전 선점 확인이 0행이라 그 2건을 보내지 않으므로
     * 송금마다 원장 호출은 1번입니다. 첫 송금은 3회 시도(대기 2분)라 B가 가져가지 않습니다.
     */
    @Test
    void reclaimedTransfersAreNotSentAgainByFirstInstance() throws Exception {
        assumeTrue(processingCount() == 0, "DB에 다른 PROCESSING 송금이 없어야 합니다.");
        UUID sender = member(10_000L);
        UUID receiver = member(0L);
        Instant base = Instant.now().minusSeconds(3_600);
        UUID slow = dueProcessingTransfer(sender, receiver, "slow", base);
        jdbcTemplate.update("update transfer set attempt_count = 3 where id = ?", slow);
        UUID second = dueProcessingTransfer(sender, receiver, "second", base.plusSeconds(1));
        UUID third = dueProcessingTransfer(sender, receiver, "third", base.plusSeconds(2));
        ledger.blockOn = slow;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> serverA = executor.submit(() -> transferService.recoverDue(Instant.now(), 10));
            assertTrue(ledger.blocked.await(10, TimeUnit.SECONDS), "서버 A가 첫 송금 원장 호출에 들어가지 않았습니다.");

            int settledByB = transferService.recoverDue(Instant.now().plusSeconds(31), 10);
            ledger.release.countDown();
            int settledByA = serverA.get(10, TimeUnit.SECONDS);

            assertEquals(2, settledByB);
            assertEquals(1, settledByA);
        } finally {
            ledger.release.countDown();
            executor.shutdownNow();
        }
        assertEquals(3, ledger.registerCalls.get());
        assertEquals(Map.of(slow, 1, second, 1, third, 1), ledger.registerCallsByReference());
        for (UUID id : List.of(slow, second, third)) {
            assertEquals("COMPLETED", statusOf(id), "status " + id);
        }
        assertEquals(3, attemptCountOf(slow));
        assertEquals(1, attemptCountOf(second));
        assertEquals(1, attemptCountOf(third));
        assertInvariants(Map.of(sender, 10_000L, receiver, 0L));
    }

    /** 다른 서버가 잠근 송금은 기다리지 않고 건너뛰어 나머지만 복구하고, 잠금이 풀린 뒤 다음 주기에 복구합니다. */
    @Test
    void recoverySkipsTransferLockedElsewhereWithoutWaiting() throws Exception {
        assumeTrue(processingCount() == 0, "DB에 다른 PROCESSING 송금이 없어야 합니다.");
        UUID sender = member(10_000L);
        UUID receiver = member(0L);
        Instant base = Instant.now().minusSeconds(3_600);
        UUID locked = dueProcessingTransfer(sender, receiver, "locked", base);
        UUID free = dueProcessingTransfer(sender, receiver, "free", base.plusSeconds(1));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("select id from transfer where id = ? for update", UUID.class, locked);
            lockHeld.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "잠금 준비 시간 초과");

            int settled = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> transferService.recoverDue(Instant.now(), 10), "잠긴 행을 기다리면 안 됩니다.");

            assertEquals(1, settled);
            assertEquals("COMPLETED", statusOf(free));
            assertEquals("PROCESSING", statusOf(locked));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }

        assertEquals(1, transferService.recoverDue(Instant.now(), 10));
        assertEquals("COMPLETED", statusOf(locked));
        assertEquals(2, ledger.registerCalls.get());
        assertInvariants(Map.of(sender, 10_000L, receiver, 0L));
    }

    /**
     * 선점한 서버가 원장 호출 전에 멈추면 다른 서버는 대기 시간(30초)이 지날 때까지 가져가지 않고,
     * 지난 뒤에는 같은 reference로 한 번만 반영합니다(시도 횟수는 그대로).
     */
    @Test
    void transferClaimedByStoppedInstanceIsRecoveredOnceAfterWait() {
        assumeTrue(processingCount() == 0, "DB에 다른 PROCESSING 송금이 없어야 합니다.");
        UUID sender = member(10_000L);
        UUID receiver = member(0L);
        UUID transferId = dueProcessingTransfer(sender, receiver, "stopped", Instant.now().minusSeconds(3_600));

        // 서버가 선점만 커밋하고 원장 호출 전에 멈춘 상황입니다.
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertEquals(1, transferRepository.claimRecoveryTargets(Instant.now(), claimedAt, 10).size());

        assertEquals(0, transferService.recoverDue(Instant.now(), 10));
        assertEquals(0, ledger.registerCalls.get());

        assertEquals(1, transferService.recoverDue(claimedAt.plusSeconds(31), 10));
        assertEquals("COMPLETED", statusOf(transferId));
        assertEquals(1, attemptCountOf(transferId));
        assertEquals(1, ledger.registerCalls.get());
        assertInvariants(Map.of(sender, 10_000L, receiver, 0L));
    }

    /**
     * 서버 2대의 자동 대사가 같은 MANUAL_REVIEW 송금을 동시에 원장과 대조해도(원장 조회는 각자 함) 확정은 한 번이고
     * SYSTEM 이력도 1건입니다.
     */
    @Test
    void concurrentAutoReconciliationsResolveOnce() throws Exception {
        assumeTrue(manualReviewCount() == 0, "DB에 다른 MANUAL_REVIEW 송금이 없어야 합니다.");
        UUID transferId = manualReviewTransfer();
        Instant past = Instant.now().minusSeconds(300);
        jdbcTemplate.update("update transfer set created_at = ?, updated_at = ? where id = ?",
                past.atOffset(ZoneOffset.UTC), past.atOffset(ZoneOffset.UTC), transferId);
        // 두 서버가 모두 대상을 읽은 뒤 확정하도록 원장 조회를 늦춥니다.
        givenPostedLedgerFor(transferId, 200);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        List<Object> results = runConcurrently(2, i -> new TransferReviewReconciler(transferRepository,
                historyRepository, ledgerReader, transactionManager, meterRegistry).reconcile(Instant.now(), 10));

        assertEquals(1, results.stream().mapToInt(Integer.class::cast).sum());
        verify(ledgerFeignApi, times(2)).getTransaction("TRANSFER", transferId);
        assertEquals("COMPLETED", statusOf(transferId));
        assertEquals(1, historyCount(transferId));
        assertEquals("SYSTEM", jdbcTemplate.queryForObject(
                "select actor_type from transfer_status_history where transfer_id = ?", String.class, transferId));
        assertEquals(1.0, meterRegistry.get("transfer.manual_review.auto_resolved").counter().count());
    }

    /** 같은 운영자·같은 내용의 확정 요청이 동시에 와도 상태 변경·이력은 1번이고, 늦은 요청은 같은 결과(200)를 받습니다. */
    @Test
    void concurrentSameResolutionsApplyOnceAndAllSucceed() throws Exception {
        UUID transferId = manualReviewTransfer();
        UUID ledgerTransactionId = givenPostedLedgerFor(transferId);
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), AuthRole.MASTER);
        AdminTransferResolveRequest request =
                new AdminTransferResolveRequest(TransferStatus.COMPLETED, "원장 POSTED 확인", ledgerTransactionId);

        List<Object> results = runConcurrently(8, i -> transferAdminService.resolve(admin, transferId, request));

        for (Object result : results) {
            assertThat(result).isInstanceOf(AdminTransferResponse.class);
            assertEquals(TransferStatus.COMPLETED, ((AdminTransferResponse) result).status());
        }
        assertEquals("COMPLETED", statusOf(transferId));
        assertEquals(1, historyCount(transferId));
    }

    /** 서로 다른 운영자의 확정 요청이 동시에 오면 하나만 반영되고 나머지는 409이며, 이력은 1건입니다. */
    @Test
    void concurrentDifferentResolutionsApplyOnlyOne() throws Exception {
        UUID transferId = manualReviewTransfer();
        UUID ledgerTransactionId = givenPostedLedgerFor(transferId);

        List<Object> results = runConcurrently(8, i -> transferAdminService.resolve(
                new AuthenticatedUser(UUID.randomUUID(), AuthRole.MASTER), transferId,
                new AdminTransferResolveRequest(TransferStatus.COMPLETED, "운영자 " + i, ledgerTransactionId)));

        long succeeded = results.stream().filter(AdminTransferResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(result -> result instanceof BusinessException exception
                        && exception.getErrorCode() == CommonErrorCode.CONFLICT)
                .count();
        assertEquals(1, succeeded);
        assertEquals(7, conflicts);
        assertEquals("COMPLETED", statusOf(transferId));
        assertEquals(1, historyCount(transferId));
    }

    /** 원장을 부르기 전 단계(지갑·회원 조회)가 실패하면 송금을 저장하지 않고, 같은 키로 다시 요청하면 처음처럼 처리됩니다. */
    @ParameterizedTest
    @EnumSource(UpstreamFailure.class)
    void upstreamFailureBeforeLedgerSavesNothingAndSameKeyWorksAfterRecovery(UpstreamFailure failure) {
        UUID sender = member(10_000L);
        UUID receiver = member(0L);
        TransferCreateRequest request = new TransferCreateRequest(receiver, 1_000L, "KRW", null);
        failure.inject(this, sender, receiver);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> transferService.request(sender, "upstream", request));

        assertEquals(failure.expected, exception.getErrorCode());
        assertEquals(0L, transferCount(sender));
        assertEquals(0, ledger.registerCalls.get());

        givenMember(sender);
        givenMember(receiver);
        TransferResponse response = transferService.request(sender, "upstream", request);

        assertEquals(TransferStatus.COMPLETED, response.status());
        assertInvariants(Map.of(sender, 10_000L, receiver, 0L));
    }

    /** 원장 호출 전 단계의 장애 종류입니다. 연결 거부·503은 503, 시간 초과는 504로 응답합니다(common FeignErrors). */
    enum UpstreamFailure {
        SENDER_WALLET_503(CommonErrorCode.SERVICE_UNAVAILABLE) {
            @Override
            void inject(TransferConsistencyIntegrationTest test, UUID sender, UUID receiver) {
                when(test.walletFeignApi.getWallet(sender)).thenThrow(serviceUnavailable("WalletFeignApi#getWallet(UUID)"));
            }
        },
        RECIPIENT_USER_503(CommonErrorCode.SERVICE_UNAVAILABLE) {
            @Override
            void inject(TransferConsistencyIntegrationTest test, UUID sender, UUID receiver) {
                when(test.userFeignApi.getActiveUser(receiver))
                        .thenThrow(serviceUnavailable("UserFeignApi#getActiveUser(UUID)"));
            }
        },
        RECEIVER_WALLET_TIMEOUT(CommonErrorCode.GATEWAY_TIMEOUT) {
            @Override
            void inject(TransferConsistencyIntegrationTest test, UUID sender, UUID receiver) {
                when(test.walletFeignApi.getWallet(receiver)).thenThrow(readTimeout("http://wallet-service/internal/v1/wallets"));
            }
        };

        final ErrorCode expected;

        UpstreamFailure(ErrorCode expected) {
            this.expected = expected;
        }

        abstract void inject(TransferConsistencyIntegrationTest test, UUID sender, UUID receiver);
    }

    // ---- 불변식 ----

    /** 시작 잔액이 주어진 회원들의 송금 전체에 대해 클래스 주석의 불변식 ①~⑥을 확인합니다. */
    private void assertInvariants(Map<UUID, Long> initialBalances) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UUID user : initialBalances.keySet()) {
            rows.addAll(jdbcTemplate.queryForList("""
                    select id, sender_id, receiver_id, source_wallet_id, target_wallet_id, amount, status,
                           failure_reason, review_reason, idempotency_key
                    from transfer where sender_id = ?""", user));
        }
        Map<UUID, Long> expected = new HashMap<>();
        initialBalances.forEach((user, balance) -> expected.put(walletOf.get(user), balance));
        Set<String> senderKeys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String status = (String) row.get("status");
            // ③ 송금인·멱등성 키당 1건
            assertThat(senderKeys.add(row.get("sender_id") + "/" + row.get("idempotency_key"))).isTrue();
            // ⑤ 복구 후 PROCESSING 없음
            assertThat(status).isNotEqualTo("PROCESSING");
            // ⑥ 상태와 사유 컬럼 조합
            assertEquals("FAILED".equals(status), row.get("failure_reason") != null, "failure_reason " + id);
            assertEquals("MANUAL_REVIEW".equals(status), row.get("review_reason") != null, "review_reason " + id);
            // ① COMPLETED ↔ 원장 반영 1회, ② FAILED는 원장 반영 없음
            assertEquals("COMPLETED".equals(status) ? 1 : 0, ledger.applied(id), "ledger applied " + id);
            if ("COMPLETED".equals(status)) {
                long amount = ((Number) row.get("amount")).longValue();
                expected.merge((UUID) row.get("source_wallet_id"), -amount, Long::sum);
                expected.merge((UUID) row.get("target_wallet_id"), amount, Long::sum);
            }
        }
        // 원장에 반영된 reference는 모두 이 회원들의 COMPLETED 송금입니다(송금 없이 원장만 반영된 거래 없음).
        Set<UUID> completedIds = new HashSet<>();
        rows.stream().filter(row -> "COMPLETED".equals(row.get("status")))
                .forEach(row -> completedIds.add((UUID) row.get("id")));
        assertEquals(completedIds, ledger.appliedReferences());
        // ④ 지갑별 잔액 = 시작 잔액 ± 완료 송금, 음수 없음, 합계 보존
        long total = 0;
        for (Map.Entry<UUID, Long> wallet : expected.entrySet()) {
            assertEquals(wallet.getValue(), ledger.balance(wallet.getKey()), "balance " + wallet.getKey());
            assertThat(ledger.balance(wallet.getKey())).isNotNegative();
            total += ledger.balance(wallet.getKey());
        }
        assertEquals(initialBalances.values().stream().mapToLong(Long::longValue).sum(), total);
    }

    // ---- 준비·조회 ----

    /** ACTIVE 회원·ACTIVE 지갑을 만들고 테스트용 원장에 시작 잔액을 넣습니다. */
    private UUID member(long balance) {
        UUID user = UUID.randomUUID();
        users.add(user);
        walletOf.put(user, UUID.randomUUID());
        ledger.balances.put(walletOf.get(user), balance);
        givenMember(user);
        return user;
    }

    /** 장애를 넣었던 호출도 다시 정상 응답으로 바꿀 수 있도록 doReturn으로 정합니다(when은 기존 스텁을 실행함). */
    private void givenMember(UUID user) {
        doReturn(ApiResponse.success(new WalletFeignApi.WalletResponse(walletOf.get(user), "ACTIVE")))
                .when(walletFeignApi).getWallet(user);
        doReturn(ApiResponse.success(new UserFeignApi.UserResponse(user, "홍길동")))
                .when(userFeignApi).getActiveUser(user);
    }

    /** 결과 모름 5회로 MANUAL_REVIEW가 된 송금(KRW 1,000)을 커밋해 둡니다. */
    private UUID manualReviewTransfer() {
        UUID sender = member(0L);
        UUID receiver = member(0L);
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2099-03-01T00:00:00Z");
        jdbcTemplate.update("""
                        insert into transfer (id, sender_id, receiver_id, source_wallet_id, target_wallet_id, amount,
                            currency, status, failure_reason, attempt_count, review_reason, memo, idempotency_key,
                            created_at, updated_at)
                        values (?, ?, ?, ?, ?, 1000, 'KRW', 'MANUAL_REVIEW', null, 5, 'RETRY_EXHAUSTED', null, ?, ?, ?)""",
                id, sender, receiver, walletOf.get(sender), walletOf.get(receiver), "review-" + id,
                created.atOffset(ZoneOffset.UTC), created.atOffset(ZoneOffset.UTC));
        return id;
    }

    /** 결과 모름 1회로 처리 중이고 재시도 대기 시간이 지난 송금(KRW 1,000)을 커밋해 둡니다. */
    private UUID dueProcessingTransfer(UUID sender, UUID receiver, String key, Instant at) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        insert into transfer (id, sender_id, receiver_id, source_wallet_id, target_wallet_id, amount,
                            currency, status, failure_reason, attempt_count, review_reason, memo, idempotency_key,
                            created_at, updated_at)
                        values (?, ?, ?, ?, ?, 1000, 'KRW', 'PROCESSING', null, 1, null, null, ?, ?, ?)""",
                id, sender, receiver, walletOf.get(sender), walletOf.get(receiver), key,
                at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC));
        return id;
    }

    private long processingCount() {
        return jdbcTemplate.queryForObject("select count(*) from transfer where status = 'PROCESSING'", Long.class);
    }

    private long manualReviewCount() {
        return jdbcTemplate.queryForObject("select count(*) from transfer where status = 'MANUAL_REVIEW'", Long.class);
    }

    private UUID givenPostedLedgerFor(UUID transferId) {
        return givenPostedLedgerFor(transferId, 0);
    }

    /** 원장 조회가 (delayMillis 뒤에) 이 송금과 같은 항목의 POSTED 거래를 돌려주게 하고 그 거래 ID를 반환합니다. */
    private UUID givenPostedLedgerFor(UUID transferId, long delayMillis) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select source_wallet_id, target_wallet_id from transfer where id = ?", transferId);
        UUID ledgerTransactionId = UUID.randomUUID();
        List<LedgerFeignApi.EntryResult> entries = List.of(
                new LedgerFeignApi.EntryResult("USER", (UUID) row.get("source_wallet_id"), "WALLET", "KRW", "DEBIT",
                        1000L, "TRANSFER_OUT"),
                new LedgerFeignApi.EntryResult("USER", (UUID) row.get("target_wallet_id"), "WALLET", "KRW", "CREDIT",
                        1000L, "TRANSFER_IN"));
        ApiResponse<LedgerFeignApi.TransactionResultResponse> posted = ApiResponse.success(
                new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, "POSTED", null, entries));
        doAnswer(invocation -> {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            return posted;
        }).when(ledgerFeignApi).getTransaction(eq("TRANSFER"), eq(transferId));
        return ledgerTransactionId;
    }

    private String statusOf(UUID transferId) {
        return jdbcTemplate.queryForObject("select status from transfer where id = ?", String.class, transferId);
    }

    private int attemptCountOf(UUID transferId) {
        return jdbcTemplate.queryForObject("select attempt_count from transfer where id = ?", Integer.class, transferId);
    }

    private long transferCount(UUID sender) {
        return jdbcTemplate.queryForObject("select count(*) from transfer where sender_id = ?", Long.class, sender);
    }

    private int historyCount(UUID transferId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from transfer_status_history where transfer_id = ?", Integer.class, transferId);
    }

    /** 작업 n개를 동시에 시작하고, 각 결과(반환값 또는 던진 예외)를 순서대로 돌려줍니다. */
    private static List<Object> runConcurrently(int count, IndexedTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit((Callable<Object>) () -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.run(index);
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            // 모든 스레드가 출발선에 서지 못하면 동시 실행이 아니므로 검증 결과를 믿을 수 없습니다.
            assertTrue(ready.await(10, TimeUnit.SECONDS), "동시 실행 준비 시간 초과");
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        Object run(int index);
    }

    private static FeignException serviceUnavailable(String methodKey) {
        Response response = Response.builder()
                .status(503)
                .reason("error")
                .request(Request.create(Request.HttpMethod.GET, "http://upstream", Map.of(), null, UTF_8, null))
                .headers(Map.of())
                .body("""
                        {"success":false,"data":null,"error":{"code":"COMMON_SERVICE_UNAVAILABLE","message":"error","details":[]}}""",
                        UTF_8)
                .build();
        return FeignException.errorStatus(methodKey, response);
    }

    private static RetryableException readTimeout(String url) {
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.GET,
                new SocketTimeoutException("Read timed out"), (Long) null,
                Request.create(Request.HttpMethod.GET, url, Map.of(), null, UTF_8, null));
    }

    /**
     * 원장 계약을 흉내 내는 테스트용 원장입니다. 지갑별 잔액(KRW)을 기억하고, 거래 등록은 한 번에 하나씩(계정 잠금처럼) 처리합니다.
     * 같은 reference는 처음 결과(POSTED면 같은 거래 ID, 거절이면 같은 422)를 돌려주고 다시 처리하지 않습니다.
     * 잔액이 부족하면 차감·증가 모두 없이 422 LEDGER_REJECTED_INSUFFICIENT_BALANCE입니다.
     */
    private static final class FakeLedger {

        final Map<UUID, Long> balances = new HashMap<>();
        final AtomicInteger registerCalls = new AtomicInteger();
        private final Map<UUID, Object> results = new HashMap<>();
        private final Map<UUID, Integer> appliedCount = new HashMap<>();
        private final Map<UUID, Integer> callsByReference = new HashMap<>();
        volatile int delayMillis;
        volatile boolean loseFirstResponse;
        /** 이 reference의 등록 요청은 release가 열릴 때까지 멈춥니다(원장 호출이 길어지는 상황). 들어오면 blocked를 엽니다. */
        volatile UUID blockOn;
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        ApiResponse<LedgerFeignApi.TransactionResponse> register(LedgerFeignApi.RegisterRequest request)
                throws InterruptedException {
            registerCalls.incrementAndGet();
            synchronized (this) {
                callsByReference.merge(request.referenceId(), 1, Integer::sum);
            }
            if (request.referenceId().equals(blockOn)) {
                blocked.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("원장 호출 대기 시간 초과");
                }
            }
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            Object result;
            boolean firstTime;
            synchronized (this) {
                firstTime = !results.containsKey(request.referenceId());
                if (firstTime) {
                    results.put(request.referenceId(), apply(request));
                }
                result = results.get(request.referenceId());
            }
            if (firstTime && loseFirstResponse) {
                loseFirstResponse = false;
                throw new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                        new SocketTimeoutException("Read timed out"), (Long) null,
                        Request.create(Request.HttpMethod.POST, LEDGER_URL, Map.of(), null, UTF_8, null));
            }
            if (result instanceof UUID transactionId) {
                return ApiResponse.success(new LedgerFeignApi.TransactionResponse(transactionId, "POSTED"));
            }
            Response response = Response.builder()
                    .status(422)
                    .reason("error")
                    .request(Request.create(Request.HttpMethod.POST, LEDGER_URL, Map.of(), null, UTF_8, null))
                    .headers(Map.of())
                    .body("""
                            {"success":false,"data":null,"error":{"code":"%s","message":"error","details":[]}}"""
                            .formatted(result), UTF_8)
                    .build();
            throw FeignException.errorStatus("LedgerFeignApi#register(RegisterRequest)", response);
        }

        /** 한 거래의 모든 DEBIT을 감당할 수 있을 때만 모든 항목을 반영합니다(부분 반영 없음). */
        private Object apply(LedgerFeignApi.RegisterRequest request) {
            for (LedgerFeignApi.Entry entry : request.entries()) {
                if ("DEBIT".equals(entry.direction()) && balances.getOrDefault(entry.walletId(), 0L) < entry.amount()) {
                    return "LEDGER_REJECTED_INSUFFICIENT_BALANCE";
                }
            }
            for (LedgerFeignApi.Entry entry : request.entries()) {
                long delta = "DEBIT".equals(entry.direction()) ? -entry.amount() : entry.amount();
                balances.merge(entry.walletId(), delta, Long::sum);
            }
            appliedCount.merge(request.referenceId(), 1, Integer::sum);
            return UUID.randomUUID();
        }

        synchronized long balance(UUID walletId) {
            return balances.getOrDefault(walletId, 0L);
        }

        synchronized int applied(UUID reference) {
            return appliedCount.getOrDefault(reference, 0);
        }

        synchronized Map<UUID, Integer> registerCallsByReference() {
            return new HashMap<>(callsByReference);
        }

        synchronized Set<UUID> appliedReferences() {
            return new HashSet<>(appliedCount.keySet());
        }
    }
}
