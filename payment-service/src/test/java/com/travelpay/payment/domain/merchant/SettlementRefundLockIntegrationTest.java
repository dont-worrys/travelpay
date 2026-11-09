package com.travelpay.payment.domain.merchant;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentHistoryRepository;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산 대상 조회(for update)와 환불 시작의 결제 잠금 조회가 같은 결제를 동시에 다루지 않는지 실제 PostgreSQL에서 확인합니다.
 * 두 트랜잭션이 서로의 커밋을 봐야 해서 테스트 트랜잭션 롤백을 쓰지 않고, 만든 데이터는 끝난 뒤 지웁니다.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "transfer.recovery.enabled=false",
        "payment.settlement-recovery.enabled=false"})
class SettlementRefundLockIntegrationTest extends PostgresContainerSupport {

    private static final Instant CUTOFF = Instant.parse("2026-11-04T00:00:00Z");
    // 다른 트랜잭션이 잠금을 기다리는지 볼 시간
    private static final long BLOCK_CHECK_MILLIS = 500;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private SettlementItemRepository settlementItemRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private ExecutorService executor;
    private UUID merchantId;
    private UUID paymentId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newSingleThreadExecutor();
        tx.executeWithoutResult(status -> {
            merchantId = merchantRepository.save(
                    Merchant.create("잠금 가맹점", CurrencyCode.KRW, UUID.randomUUID())).getId();
            Payment payment = paymentRepository.save(Payment.create(UUID.randomUUID(), UUID.randomUUID(),
                    "lock-it-" + UUID.randomUUID(), merchantId, "잠금 가맹점", "테스트 주문",
                    CurrencyCode.KRW, 10_000L));
            paymentId = payment.getId();
            PaymentHistory history = PaymentHistory.of(
                    paymentId, PaymentStatus.PENDING, PaymentStatus.APPROVED, null, PaymentActor.SYSTEM);
            paymentHistoryRepository.save(history);
            // 엔티티를 DB에 쓴 뒤 상태와 승인 시각을 맞춤
            entityManager.flush();
            jdbcTemplate.update("update payments set status = 'APPROVED' where id = ?", paymentId);
            jdbcTemplate.update("update payment_histories set created_at = ? where id = ?",
                    Timestamp.from(CUTOFF.minus(Duration.ofDays(1))), history.getId());
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbcTemplate.update("delete from settlement_items where payment_id = ?", paymentId);
        jdbcTemplate.update("delete from settlements where merchant_id = ?", merchantId);
        jdbcTemplate.update("delete from payment_histories where payment_id = ?", paymentId);
        jdbcTemplate.update("delete from payments where id = ?", paymentId);
        jdbcTemplate.update("delete from merchants where id = ?", merchantId);
    }

    // 정산이 먼저 잠그면 환불 시작은 정산 커밋을 기다렸다가 정산 항목을 봄 (→ ALREADY_SETTLED)
    @Test
    void refundWaitsForSettlementAndSeesItem() throws Exception {
        Future<Boolean> refundSeesSettled = tx.execute(status -> {
            List<Payment> targets = paymentRepository.findSettlementTargets(merchantId, CUTOFF, 1_000);
            assertEquals(List.of(paymentId), targets.stream().map(Payment::getId).toList());
            Settlement settlement = settlementRepository.save(Settlement.request(merchantId, UUID.randomUUID(),
                    "lock-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CurrencyCode.KRW,
                    10_000L, CUTOFF));
            settlementItemRepository.saveAll(List.of(SettlementItem.of(paymentId, settlement.getId(), 10_000L)));

            Future<Boolean> refund = executor.submit(() -> tx.execute(inner -> {
                paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
                return settlementItemRepository.existsByPaymentId(paymentId);
            }));
            sleepQuietly();
            assertFalse(refund.isDone(), "정산이 잠근 동안 환불 시작은 기다려야 함");
            return refund;
        });

        assertTrue(refundSeesSettled.get(5, TimeUnit.SECONDS));
    }

    // 환불이 먼저 잠그고 REFUND_PENDING으로 바꾸면 정산 대상 조회는 기다렸다가 그 결제를 빼고 돌려줌
    @Test
    void settlementWaitsForRefundAndExcludesPayment() throws Exception {
        Future<List<UUID>> settlementTargets = tx.execute(status -> {
            paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
            jdbcTemplate.update("update payments set status = 'REFUND_PENDING' where id = ?", paymentId);

            Future<List<UUID>> targets = executor.submit(() -> tx.execute(inner ->
                    paymentRepository.findSettlementTargets(merchantId, CUTOFF, 1_000).stream()
                            .map(Payment::getId).toList()));
            sleepQuietly();
            assertFalse(targets.isDone(), "환불이 잠근 동안 정산 대상 조회는 기다려야 함");
            return targets;
        });

        assertTrue(settlementTargets.get(5, TimeUnit.SECONDS).isEmpty());
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(BLOCK_CHECK_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
