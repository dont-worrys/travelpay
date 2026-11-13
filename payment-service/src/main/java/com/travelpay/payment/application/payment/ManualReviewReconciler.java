package com.travelpay.payment.application.payment;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.application.merchant.SettlementReviewService;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * MANUAL_REVIEW 결제·환불·정산을 원장 결과로 자동 처리합니다(결제 복구 스케줄러가 주기적으로 호출).
 * 판단은 운영자 처리와 같은 규칙이고, 원장에 결과가 확정된 건(POSTED 항목 일치, REJECTED)만 처리합니다.
 * 원장 기록 없음·항목 불일치는 MANUAL_REVIEW 그대로 운영자에게 남깁니다. 운영자 처리와 겹쳐도 한쪽만 반영됩니다.
 * <ul>
 *     <li>MANUAL_REVIEW로 바뀐 지 1분이 안 된 건은 건너뜁니다(마지막 원장 요청이 원장에서 끝날 시간).</li>
 *     <li>종류마다 (생성 시각, ID) 커서로 이어서 보고, 끝까지 가면 처음부터 다시 봅니다. 건너뛴 건이 앞에 쌓여도 뒤의 건을 봅니다.</li>
 *     <li>한 번에 쓰는 시간을 제한하고(원장 조회는 서킷 밖이라 느리면 건마다 최대 응답 시간까지 기다림),
 *         원장 조회가 연달아 실패하면 원장 장애로 보고 남은 건은 다음 차례로 넘깁니다.</li>
 * </ul>
 * 스케줄러 한 스레드에서만 부릅니다. 커서는 메모리에만 있어 재기동하면 처음부터 봅니다.
 */
@Slf4j
@Component
public class ManualReviewReconciler {

    /** MANUAL_REVIEW로 바뀐 직후의 건은 건너뜀 */
    static final Duration SETTLE_DELAY = Duration.ofMinutes(1);
    /** 종류별 한 번에 보는 건수 */
    static final int BATCH_SIZE = 10;
    /** 한 번에 쓰는 시간 한도. 넘으면 남은 건은 다음 차례로 */
    static final Duration TIME_BUDGET = Duration.ofSeconds(5);
    /** 원장 조회가 연달아 이만큼 실패하면 원장 장애로 보고 멈춤 */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final Instant CURSOR_START = Instant.EPOCH;
    private static final UUID CURSOR_START_ID = new UUID(0L, 0L);

    private final List<Target<?>> targets;
    private final MeterRegistry meterRegistry;
    private final LongSupplier nanoClock;
    // 매번 같은 종류부터 시작하면 원장이 느릴 때 뒤 종류가 계속 밀리므로 시작 종류를 돌아가며 바꿈
    private int startIndex;

    @Autowired
    public ManualReviewReconciler(PaymentRepository paymentRepository, RefundRepository refundRepository,
                                  SettlementRepository settlementRepository, PaymentReviewService paymentReviewService,
                                  SettlementReviewService settlementReviewService, MeterRegistry meterRegistry) {
        this(paymentRepository, refundRepository, settlementRepository, paymentReviewService, settlementReviewService,
                meterRegistry, System::nanoTime);
    }

    // 테스트에서 시간 한도를 확인하려고 시각 공급자를 받음
    ManualReviewReconciler(PaymentRepository paymentRepository, RefundRepository refundRepository,
                           SettlementRepository settlementRepository, PaymentReviewService paymentReviewService,
                           SettlementReviewService settlementReviewService, MeterRegistry meterRegistry,
                           LongSupplier nanoClock) {
        this.targets = List.of(
                new Target<>("payment", paymentRepository::findManualReviewsAfter,
                        Payment::getCreatedAt, Payment::getId, paymentReviewService::autoResolvePayment),
                new Target<>("refund", refundRepository::findManualReviewsAfter,
                        Refund::getCreatedAt, Refund::getId, paymentReviewService::autoResolveRefund),
                new Target<>("settlement", settlementRepository::findManualReviewsAfter,
                        Settlement::getCreatedAt, Settlement::getId, settlementReviewService::autoResolve));
        this.meterRegistry = meterRegistry;
        this.nanoClock = nanoClock;
    }

    /** 결제·환불·정산 MANUAL_REVIEW를 종류별로 최대 BATCH_SIZE건 자동 처리하고, 처리한 건수를 돌려줍니다. */
    public int reconcile(Instant now) {
        Round round = new Round(nanoClock.getAsLong() + TIME_BUDGET.toNanos());
        Instant updatedBefore = now.minus(SETTLE_DELAY);
        int first = startIndex;
        startIndex = (startIndex + 1) % targets.size();
        for (int i = 0; i < targets.size() && !round.stopped; i++) {
            targets.get((first + i) % targets.size()).run(updatedBefore, round);
        }
        if (round.resolved > 0) {
            log.info("MANUAL_REVIEW 자동 처리 {}건", round.resolved);
        }
        return round.resolved;
    }

    @FunctionalInterface
    private interface CursorQuery<T> {
        List<T> find(Instant updatedBefore, Instant afterCreatedAt, UUID afterId, int limit);
    }

    /** 한 번의 자동 처리에서 종류끼리 함께 쓰는 시간 한도·연속 실패 수 */
    private final class Round {
        private final long deadline;
        private int consecutiveFailures;
        private int resolved;
        private boolean stopped;

        private Round(long deadline) {
            this.deadline = deadline;
        }

        private boolean overBudget() {
            return nanoClock.getAsLong() >= deadline;
        }
    }

    /** 처리 대상 한 종류(결제·환불·정산)와 그 커서 */
    private final class Target<T> {
        private final String type;
        private final CursorQuery<T> query;
        private final Function<T, Instant> createdAt;
        private final Function<T, UUID> id;
        private final Function<T, AutoResolveOutcome> resolver;
        private Instant cursorCreatedAt = CURSOR_START;
        private UUID cursorId = CURSOR_START_ID;

        private Target(String type, CursorQuery<T> query, Function<T, Instant> createdAt, Function<T, UUID> id,
                       Function<T, AutoResolveOutcome> resolver) {
            this.type = type;
            this.query = query;
            this.createdAt = createdAt;
            this.id = id;
            this.resolver = resolver;
        }

        private void run(Instant updatedBefore, Round round) {
            Instant startCreatedAt = cursorCreatedAt;
            UUID startId = cursorId;
            List<T> items = query.find(updatedBefore, cursorCreatedAt, cursorId, BATCH_SIZE);
            for (T item : items) {
                if (round.overBudget()) {
                    // 커서는 마지막으로 본 건에 있으므로 다음 차례에 이어서 봄
                    log.info("MANUAL_REVIEW 자동 처리 시간 한도({}초)를 넘어 남은 {}는 다음 차례로 넘깁니다.",
                            TIME_BUDGET.toSeconds(), type);
                    round.stopped = true;
                    return;
                }
                if (!handle(item, round)) {
                    // 원장 장애로 멈춤. 조회하지 못한 건을 건너뛰지 않도록 다음 차례는 이번 시작 위치부터
                    cursorCreatedAt = startCreatedAt;
                    cursorId = startId;
                    round.stopped = true;
                    return;
                }
                cursorCreatedAt = createdAt.apply(item);
                cursorId = id.apply(item);
            }
            if (items.size() < BATCH_SIZE) {
                // 끝까지 봤으므로 다음 차례는 처음부터
                cursorCreatedAt = CURSOR_START;
                cursorId = CURSOR_START_ID;
            }
        }

        // 한 건 처리. 원장 조회가 연달아 실패해 멈춰야 하면 false
        private boolean handle(T item, Round round) {
            AutoResolveOutcome outcome;
            try {
                outcome = resolver.apply(item);
            } catch (BusinessException e) {
                if (e.getErrorCode() != PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE) {
                    log.error("MANUAL_REVIEW 자동 처리 실패 type={} id={}", type, id.apply(item), e);
                    return true;
                }
                log.warn("MANUAL_REVIEW 자동 처리 중 원장 조회 실패 type={} id={}", type, id.apply(item));
                if (++round.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("원장 조회가 {}번 연달아 실패해 MANUAL_REVIEW 자동 처리를 다음 차례로 미룹니다.",
                            MAX_CONSECUTIVE_FAILURES);
                    return false;
                }
                return true;
            } catch (RuntimeException e) {
                // 데이터 이상 등 이 건만의 문제. 다른 건은 계속 처리
                log.error("MANUAL_REVIEW 자동 처리 실패 type={} id={}", type, id.apply(item), e);
                return true;
            }
            round.consecutiveFailures = 0;
            if (outcome == AutoResolveOutcome.RESOLVED) {
                round.resolved++;
                Counter.builder("payment.manual_review.auto_resolved")
                        .description("MANUAL_REVIEW를 원장 결과로 자동 처리한 건수")
                        .tag("type", type)
                        .register(meterRegistry)
                        .increment();
            }
            return true;
        }
    }
}
