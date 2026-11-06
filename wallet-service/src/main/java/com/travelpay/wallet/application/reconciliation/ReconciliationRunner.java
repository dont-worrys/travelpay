package com.travelpay.wallet.application.reconciliation;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationPeriod;
import com.travelpay.wallet.application.reconciliation.dto.ReconciliationRunResult;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.reconciliation.ReconciliationAlreadyRunningException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 대사 실행 경로 (스케줄러·관리자 수동 실행). 새 실행 전에 오래된 RUNNING을 정리하고,
 * 정리 후에도 RUNNING이 남아 있으면 시작하지 않는다. 외부 호출이 있어 트랜잭션 없이 동작한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationRunner {

    static final String STALE_REASON = "실행 시간 초과로 정리됨 (RUNNING 만료)";

    private final ChargeReconciliationService reconciliationService;
    private final ReconciliationPeriodCalculator periodCalculator;
    private final ReconciliationTransactionSteps steps;
    private final ReconciliationSettings settings;

    /** 스케줄러 1회. 실행 중이거나 닫힌 기간이 없으면 건너뛴다 (empty) */
    public Optional<ReconciliationRunResult> runScheduled() {
        Instant now = Instant.now();
        if (!cleanUpStaleAndCheckIdle(now)) {
            log.info("충전 대사 건너뜀: 실행 중인 대사가 있음");
            return Optional.empty();
        }
        Optional<ReconciliationPeriod> period = periodCalculator.next(now);
        if (period.isEmpty()) {
            return Optional.empty();
        }
        ReconciliationPeriod p = period.get();
        try {
            return Optional.of(reconciliationService.run(p.from(), p.to(), p.overlapFrom()));
        } catch (ReconciliationAlreadyRunningException e) {
            log.info("충전 대사 건너뜀: 다른 실행이 먼저 시작함");
            return Optional.empty();
        }
    }

    /**
     * 관리자 수동 실행. from·to 둘 다 없으면 스케줄러와 같은 기간 계산, 둘 다 있으면 그 기간 재대사.
     * 하나만 있음·from ≥ to·to > 지금 − 여유·최대 기간 초과는 400, 실행 중이거나 닫힌 기간이 없으면 409
     */
    public ReconciliationRunResult runManual(Instant from, Instant to) {
        if ((from == null) != (to == null)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Instant now = Instant.now();
        ReconciliationPeriod period = from == null ? null : validatedPeriod(from, to, now);
        if (!cleanUpStaleAndCheckIdle(now)) {
            throw new BusinessException(WalletErrorCode.RECONCILIATION_ALREADY_RUNNING);
        }
        if (period == null) {
            period = periodCalculator.next(now)
                    .orElseThrow(() -> new BusinessException(WalletErrorCode.RECONCILIATION_PERIOD_NOT_READY));
        }
        try {
            return reconciliationService.run(period.from(), period.to(), period.overlapFrom());
        } catch (ReconciliationAlreadyRunningException e) {
            throw new BusinessException(WalletErrorCode.RECONCILIATION_ALREADY_RUNNING);
        }
    }

    /** 오래된 RUNNING을 조건부 UPDATE로 정리한 뒤, RUNNING이 남아 있지 않으면 true */
    private boolean cleanUpStaleAndCheckIdle(Instant now) {
        int cleaned = steps.failStaleRunning(now.minus(settings.runningTimeout()), STALE_REASON);
        if (cleaned > 0) {
            log.warn("오래된 충전 대사 RUNNING 정리 count={} runningTimeout={}", cleaned, settings.runningTimeout());
        }
        return !steps.existsRunning();
    }

    /** DB 정밀도(마이크로초)로 올린 뒤 검증한다. 올린 값도 닫힌 기간 안이어야 한다 */
    private ReconciliationPeriod validatedPeriod(Instant from, Instant to, Instant now) {
        Instant f = ceilToMicros(from);
        Instant t = ceilToMicros(to);
        if (!f.isBefore(t) || t.isAfter(settings.closedUntil(now))
                || Duration.between(f, t).compareTo(settings.maxPeriod()) > 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return new ReconciliationPeriod(f, t, null);
    }

    private static Instant ceilToMicros(Instant instant) {
        Instant truncated = instant.truncatedTo(ChronoUnit.MICROS);
        if (truncated.equals(instant)) {
            return instant;
        }
        try {
            return truncated.plus(1, ChronoUnit.MICROS);
        } catch (DateTimeException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }
}
