package com.travelpay.payment.transfer.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.travelpay.payment.transfer.client.TransferLedgerReader;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.domain.TransferStatusHistory;
import com.travelpay.payment.transfer.domain.TransferStatusHistoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MANUAL_REVIEW 송금을 원장 결과로 자동 확정합니다(자동 대사). 운영자 확정({@link TransferAdminService#resolve})과 같은 판단
 * {@link TransferResolution}을 쓰고, 결과가 확정적인 경우만 바꿉니다.
 * <ul>
 *     <li>원장 POSTED + 항목이 송금과 같음 → COMPLETED</li>
 *     <li>원장 REJECTED → FAILED(실패 사유는 운영자 확정과 같음, 원장 미수락 송금은 NOT_PROCESSED)</li>
 *     <li>원장 기록 없음 → 그대로 둠. 재전송 여부는 운영자가 정합니다(자동으로 되돌리면 재시도 한도가 끝없이 반복됨)</li>
 *     <li>항목 불일치·원장 조회 실패 → 그대로 둠</li>
 * </ul>
 * 원장 조회는 DB 트랜잭션 밖에서 하고, MANUAL_REVIEW일 때만 바꾸는 조건부 UPDATE와 SYSTEM 이력 저장을 한 트랜잭션으로 묶습니다.
 * 운영자 확정·다른 인스턴스와 겹쳐도 조건부 UPDATE에서 한쪽만 반영됩니다.
 * 복구와 달리 선점하지 않으므로 서버가 여러 대면 같은 송금을 서버마다 원장에 조회할 수 있습니다. 조회는 상태를 바꾸지 않고
 * 양이 적어(서버당 1분 10건 이하) 그대로 두며, 원장 호출 동안 DB 잠금을 잡지 않으려는 것이기도 합니다.
 */
@Component
public class TransferReviewReconciler {

    private static final Logger log = LoggerFactory.getLogger(TransferReviewReconciler.class);

    /**
     * MANUAL_REVIEW로 바뀐 직후의 송금은 건너뜁니다. 마지막 원장 요청이 아직 처리 중일 수 있어 여유를 둡니다.
     * 원장 서버의 요청 대기 시간에는 상한이 없지만(원장 트랜잭션 3초 상한은 커넥션 획득 이후 기준), 기록 없음은 바꾸지 않고
     * 다음 주기에 다시 보므로 원장 반영이 늦어도 정합성에는 영향이 없습니다.
     */
    static final Duration SETTLE_DELAY = Duration.ofMinutes(1);
    /** 원장 조회가 연달아 실패하면 원장 장애로 보고 남은 송금은 다음 주기로 넘깁니다. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final String REASON = "원장 결과 자동 대사";

    private static final Instant CURSOR_START = Instant.EPOCH;
    private static final UUID CURSOR_START_ID = new UUID(0L, 0L);

    private final TransferRepository transferRepository;
    private final TransferStatusHistoryRepository historyRepository;
    private final TransferLedgerReader ledgerReader;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    // 다음 주기에 이어서 볼 위치. 스케줄러 한 스레드에서만 부르며, 재기동하면 처음부터 봅니다.
    private Instant cursorUpdatedAt = CURSOR_START;
    private UUID cursorId = CURSOR_START_ID;

    public TransferReviewReconciler(TransferRepository transferRepository,
            TransferStatusHistoryRepository historyRepository,
            TransferLedgerReader ledgerReader,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.historyRepository = historyRepository;
        this.ledgerReader = ledgerReader;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    /**
     * 커서 다음의 MANUAL_REVIEW 송금을 최대 limit건 원장 결과와 대조해 확정적인 것만 확정하고, 확정한 건수를 반환합니다.
     */
    public int reconcile(Instant now, int limit) {
        Instant startUpdatedAt = cursorUpdatedAt;
        UUID startId = cursorId;
        List<Transfer> targets = transferRepository.findManualReviewAfter(
                now.minus(SETTLE_DELAY), cursorUpdatedAt, cursorId, limit);
        if (targets.size() < limit) {
            // 끝까지 봤으므로 다음 주기는 처음부터 봅니다.
            resetCursor();
        }
        int resolved = 0;
        int consecutiveFailures = 0;
        for (Transfer target : targets) {
            if (targets.size() == limit) {
                cursorUpdatedAt = target.getUpdatedAt();
                cursorId = target.getId();
            }
            TransferResolution resolution;
            try {
                resolution = TransferResolution.decide(target, ledgerReader.findTransfer(target.getId()));
            } catch (RuntimeException exception) {
                log.warn("MANUAL_REVIEW 자동 대사 중 원장 거래 결과를 조회하지 못했습니다. transferId={}", target.getId(), exception);
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("원장 거래 결과를 연달아 조회하지 못해 남은 MANUAL_REVIEW 자동 대사를 다음 주기로 미룹니다.");
                    // 못 본 송금을 건너뛰지 않도록 다음 주기는 이번 주기 시작 위치부터 다시 봅니다.
                    cursorUpdatedAt = startUpdatedAt;
                    cursorId = startId;
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;
            if (apply(target, resolution)) {
                resolved++;
            }
        }
        return resolved;
    }

    /** 확정적인 결과(COMPLETED·FAILED)만 반영합니다. 반영했으면 true입니다. */
    private boolean apply(Transfer transfer, TransferResolution resolution) {
        TransferStatus target = resolution.expectedStatus();
        if (target != TransferStatus.COMPLETED && target != TransferStatus.FAILED) {
            if (Boolean.FALSE.equals(resolution.entriesMatch())) {
                log.error("원장 거래 항목이 송금과 달라 자동 대사하지 않습니다. transferId={} ledgerTransactionId={}",
                        transfer.getId(), resolution.ledgerTransactionId());
            }
            return false;
        }
        UUID ledgerTransactionId = resolution.ledgerTransactionId();
        Boolean applied = transactionTemplate.execute(status -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            int updated = transferRepository.resolveManualReview(transfer.getId(), target, resolution.failureReason(),
                    transfer.getAttemptCount(), now);
            if (updated == 0) {
                return false;
            }
            historyRepository.save(TransferStatusHistory.bySystem(transfer.getId(), TransferStatus.MANUAL_REVIEW, target,
                    REASON, ledgerTransactionId, now));
            return true;
        });
        if (!Boolean.TRUE.equals(applied)) {
            // 조회 이후 운영자나 다른 인스턴스가 먼저 확정했습니다.
            return false;
        }
        Counter.builder("transfer.manual_review.auto_resolved")
                .description("MANUAL_REVIEW를 원장 결과로 자동 확정한 송금 수")
                .tag("status", target.name())
                .register(meterRegistry)
                .increment();
        log.info("MANUAL_REVIEW 자동 대사 transferId={} reviewReason={} targetStatus={} failureReason={} ledgerTransactionId={}",
                transfer.getId(), transfer.getReviewReason(), target, resolution.failureReason(), ledgerTransactionId);
        return true;
    }

    private void resetCursor() {
        cursorUpdatedAt = CURSOR_START;
        cursorId = CURSOR_START_ID;
    }
}
