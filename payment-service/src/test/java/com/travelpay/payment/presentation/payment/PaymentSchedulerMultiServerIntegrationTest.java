package com.travelpay.payment.presentation.payment;

import com.travelpay.payment.application.payment.ManualReviewLock;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 결제 복구 스케줄러를 서버 두 대가 동시에 돌리는 상황을 실제 PostgreSQL에서 확인합니다.
 * <ul>
 *     <li>복구 일괄 선점(SKIP LOCKED): 한 서버가 선점 중인 행을 다른 서버는 건너뛰어, 두 서버가 서로 다른 건을 가져감</li>
 *     <li>MANUAL_REVIEW 자동 처리 잠금(advisory lock): 한 서버가 처리 중이면 다른 서버는 그 차례를 건너뜀</li>
 * </ul>
 * 트랜잭션을 나눠야 해서 테스트 트랜잭션을 쓰지 않고, 만든 데이터는 끝나고 지웁니다.
 * 결제·환불 선점도 같은 쿼리 구조라 정산으로 대표해 확인합니다.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "transfer.recovery.enabled=false",
        "payment.settlement-recovery.enabled=false"})
class PaymentSchedulerMultiServerIntegrationTest extends PostgresContainerSupport {

    private static final Instant CUTOFF = Instant.parse("2026-11-04T00:00:00Z");

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ManualReviewLock manualReviewLock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID merchantId;
    private final List<UUID> settlementIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        merchantId = merchantRepository.save(Merchant.create("복구 동시성 가맹점", CurrencyCode.KRW, UUID.randomUUID()))
                .getId();
        for (int i = 0; i < 6; i++) {
            Settlement settlement = settlementRepository.save(Settlement.request(merchantId, UUID.randomUUID(),
                    "key-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CurrencyCode.KRW, 1_000L,
                    CUTOFF));
            settlementIds.add(settlement.getId());
        }
        // 재시도 대기 시간이 지난 REQUESTED 정산으로 만듦. 다른 데이터보다 먼저 선점되도록 가장 오래된 시각으로
        jdbcTemplate.update("update settlements set updated_at = timestamptz '2000-01-01 00:00:00+00' "
                + "where merchant_id = ?", merchantId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from settlements where merchant_id = ?", merchantId);
        jdbcTemplate.update("delete from merchants where id = ?", merchantId);
    }

    @Test
    void twoServersClaimDifferentSettlementsWhileOneIsStillClaiming() throws Exception {
        Instant now = Instant.now();
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // 서버 A: 3건을 선점하고 커밋 전에 멈춤(행 잠금 유지)
        CompletableFuture<List<UUID>> serverA = CompletableFuture.supplyAsync(() ->
                transactionTemplate.execute(status -> {
                    List<UUID> ids = ownIds(settlementRepository.claimRecoveryTargets(now, Instant.now(), 3));
                    firstClaimed.countDown();
                    await(releaseFirst);
                    return ids;
                }));
        assertTrue(firstClaimed.await(10, TimeUnit.SECONDS));

        // 서버 B: A가 잠근 행을 기다리지 않고 건너뛰어 나머지를 가져감
        List<UUID> serverB = ownIds(settlementRepository.claimRecoveryTargets(now, Instant.now(), 1000));
        releaseFirst.countDown();
        List<UUID> claimedByA = serverA.get(10, TimeUnit.SECONDS);

        assertEquals(3, claimedByA.size());
        assertEquals(3, serverB.size());
        Set<UUID> all = new HashSet<>(claimedByA);
        all.addAll(serverB);
        assertEquals(new HashSet<>(settlementIds), all);

        // 커밋 뒤에는 수정 시각이 선점 시각이라 대기 시간이 다시 시작돼, 같은 기준으로는 아무도 다시 가져가지 않음
        assertTrue(ownIds(settlementRepository.claimRecoveryTargets(now, Instant.now(), 1000)).isEmpty());
    }

    @Test
    void onlyOneServerRunsManualReviewReconcileAtATime() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        // 서버 A: 잠금을 잡고 처리 중
        CompletableFuture<Boolean> serverA = CompletableFuture.supplyAsync(() ->
                manualReviewLock.runExclusively(() -> {
                    runs.incrementAndGet();
                    running.countDown();
                    await(finish);
                }));
        assertTrue(running.await(10, TimeUnit.SECONDS));

        // 서버 B: 이번 차례는 건너뜀
        assertFalse(manualReviewLock.runExclusively(runs::incrementAndGet));

        finish.countDown();
        assertTrue(serverA.get(10, TimeUnit.SECONDS));
        assertEquals(1, runs.get());

        // A가 끝나 잠금이 풀리면 다음 차례는 다시 잡을 수 있음
        assertTrue(manualReviewLock.runExclusively(runs::incrementAndGet));
        assertEquals(2, runs.get());
    }

    @Test
    void lockIsReleasedWhenReconcileThrows() {
        try {
            manualReviewLock.runExclusively(() -> {
                throw new IllegalStateException("자동 처리 실패");
            });
        } catch (IllegalStateException expected) {
            // 스케줄러가 로그로 남기는 예외
        }

        assertTrue(manualReviewLock.runExclusively(() -> { }));
    }

    // 다른 테스트 데이터가 섞이지 않게 이 테스트의 정산만
    private List<UUID> ownIds(List<Settlement> claimed) {
        return claimed.stream().map(Settlement::getId).filter(settlementIds::contains).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
