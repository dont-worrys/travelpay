package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.ReconciliationPeriod;
import com.travelpay.wallet.domain.reconciliation.ReconciliationRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 다음 닫힌 대사 기간을 계산한다 (스케줄러·기간 없는 수동 실행 공용).
 * <ul>
 *   <li>from: 직전 실행(COMPLETED·PARTIAL 중 period_to가 가장 늦은 실행)의 period_to − 겹침, 없으면 최초 시작점.
 *       period_to 기준이라 지난 기간 재대사가 진행 위치를 되돌리지 않는다</li>
 *   <li>to: min(지금 − 여유, from + 최대 기간). 밀린 기간은 다음 주기들이 따라잡는다</li>
 *   <li>to ≤ from이면 실행할 기간 없음</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ReconciliationPeriodCalculator {

    private final ReconciliationTransactionSteps steps;
    private final ReconciliationSettings settings;

    public Optional<ReconciliationPeriod> next(Instant now) {
        Optional<ReconciliationRun> previous = steps.findLatestClosed();
        Instant from = previous.map(run -> run.getPeriodTo().minus(settings.overlap()))
                .orElse(settings.initialFrom());
        Instant overlapFrom = previous.isPresent() ? from : null;
        Instant closedUntil = settings.closedUntil(now);
        Instant capped = from.plus(settings.maxPeriod());
        Instant to = capped.isBefore(closedUntil) ? capped : closedUntil;
        if (!from.isBefore(to)) {
            return Optional.empty();
        }
        return Optional.of(new ReconciliationPeriod(from, to, overlapFrom));
    }
}
