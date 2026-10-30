package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.charge.ChargeStatusHistory;
import com.travelpay.wallet.domain.charge.ChargeStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 충전 상태 전이 (SAGA 단계에서 사용). 현재 상태가 from일 때만 조건부 UPDATE로 바꾸고, 바뀐 경우에만 이력을 남긴다.
 * 전이와 후속 저장을 한 트랜잭션으로 묶도록 호출자 트랜잭션 안에서만 동작한다.
 *
 * TODO: 허용 전이표(from → to)는 SAGA 이슈에서 정의
 * TODO: bank_tx_id·ledger_ref·attempt_count 등 상태와 함께 바뀌는 값은 다음 이슈에서 이 조건부 UPDATE로 확장
 *       (엔티티 dirty checking으로 바꾸지 않는다)
 */
@Component
@RequiredArgsConstructor
public class ChargeStatusTransitioner {

    /** charge_requests.last_error 길이 */
    private static final int LAST_ERROR_MAX = 500;

    private final ChargeRequestRepository chargeRequestRepository;
    private final ChargeStatusHistoryRepository historyRepository;

    /**
     * failureCode·lastError가 null이면 기존 값을 유지한다.
     * 전이했으면 true, 현재 상태가 from이 아니어서 바꾸지 않았으면 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transition(UUID chargeId, ChargeStatus from, ChargeStatus to,
                              ChargeFailureCode failureCode, String lastError) {
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        String trimmedError = truncate(lastError);
        int updated = chargeRequestRepository.transitionStatus(chargeId, from, to, failureCode, trimmedError,
                Instant.now());
        if (updated == 0) {
            return false;
        }
        historyRepository.save(ChargeStatusHistory.record(chargeId, from, to,
                failureCode != null ? failureCode.name() : null, trimmedError, null));
        return true;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= LAST_ERROR_MAX) {
            return value;
        }
        return value.substring(0, LAST_ERROR_MAX);
    }
}
