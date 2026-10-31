package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.charge.ChargeStatusHistory;
import com.travelpay.wallet.domain.charge.ChargeStatusHistoryRepository;
import com.travelpay.wallet.domain.charge.IllegalChargeTransitionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 충전 상태 전이 (SAGA 단계에서 사용). 현재 상태가 from일 때만 조건부 UPDATE로 바꾸고, 바뀐 경우에만 이력을 남긴다.
 * 전이와 후속 저장을 한 트랜잭션으로 묶도록 호출자 트랜잭션 안에서만 동작한다.
 * 상태와 함께 바뀌는 값(bank_tx_id·ledger_ref·attempt_count 등)도 조건부 UPDATE로만 바꾼다 (엔티티 dirty checking 사용 안 함).
 * 허용 전이표(ChargeStatus.nextStatuses)에 없는 전이는 DB를 건드리기 전에 IllegalChargeTransitionException으로 막는다.
 */
@Component
@RequiredArgsConstructor
public class ChargeStatusTransitioner {

    /** 결과 모름이 이 횟수에 이르면 MANUAL_REVIEW */
    static final int MAX_UNKNOWN_ATTEMPTS = 5;

    /** charge_requests.last_error 길이 */
    private static final int LAST_ERROR_MAX = 500;

    private final ChargeRequestRepository chargeRequestRepository;
    private final ChargeStatusHistoryRepository historyRepository;

    public enum UnknownRecord {
        /** 현재 상태가 달라 기록하지 않음 */
        NOT_RECORDED,
        RECORDED,
        /** 기록으로 한도에 이르러 MANUAL_REVIEW로 전이함 */
        EXHAUSTED
    }

    /**
     * failureCode·lastError가 null이면 기존 값을 유지한다.
     * 전이했으면 true, 현재 상태가 from이 아니어서 바꾸지 않았으면 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transition(UUID chargeId, ChargeStatus from, ChargeStatus to,
                              ChargeFailureCode failureCode, String lastError) {
        return transition(chargeId, from, to, failureCode, lastError, null);
    }

    /** transition + 이력 trace_id 기록. attempt_count는 그대로 둔다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transition(UUID chargeId, ChargeStatus from, ChargeStatus to,
                              ChargeFailureCode failureCode, String lastError, String traceId) {
        requireTransition(chargeId, from, to);
        String trimmedError = truncate(lastError);
        int updated = chargeRequestRepository.transitionStatus(chargeId, from, to, failureCode, trimmedError,
                Instant.now());
        return recordHistory(updated, chargeId, from, to, failureCode, trimmedError, traceId);
    }

    /** 다음 단계로 전이: 단계 결과 ID를 함께 저장하고 attempt_count를 0으로 초기화한다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean advance(UUID chargeId, ChargeStatus from, ChargeStatus to,
                           ChargeStepResult result, String traceId) {
        requireTransition(chargeId, from, to);
        Objects.requireNonNull(result, "result");
        String trimmedError = truncate(result.lastError());
        int updated = chargeRequestRepository.advanceStatus(chargeId, from, to, result.failureCode(), trimmedError,
                result.bankTxId(), result.ledgerRef(), result.refundBankTxId(), Instant.now());
        return recordHistory(updated, chargeId, from, to, result.failureCode(), trimmedError, traceId);
    }

    /**
     * 결과 모름 기록: 상태는 그대로, attempt_count +1, last_error 갱신.
     * 이 기록으로 한도에 이르면 같은 트랜잭션에서 MANUAL_REVIEW(RETRY_EXHAUSTED)로 전이한다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UnknownRecord recordUnknown(UUID chargeId, ChargeStatus status, String lastError, String traceId) {
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(status, "status");
        String trimmedError = truncate(lastError);
        OptionalInt attempts = chargeRequestRepository.increaseAttempt(chargeId, status, trimmedError,
                Instant.now());
        if (attempts.isEmpty()) {
            return UnknownRecord.NOT_RECORDED;
        }
        if (attempts.getAsInt() < MAX_UNKNOWN_ATTEMPTS) {
            return UnknownRecord.RECORDED;
        }
        transition(chargeId, status, ChargeStatus.MANUAL_REVIEW, ChargeFailureCode.RETRY_EXHAUSTED, null, traceId);
        return UnknownRecord.EXHAUSTED;
    }

    private boolean recordHistory(int updated, UUID chargeId, ChargeStatus from, ChargeStatus to,
                                  ChargeFailureCode failureCode, String lastError, String traceId) {
        if (updated == 0) {
            return false;
        }
        historyRepository.save(ChargeStatusHistory.record(chargeId, from, to,
                failureCode != null ? failureCode.name() : null, lastError, traceId));
        return true;
    }

    private static void requireTransition(UUID chargeId, ChargeStatus from, ChargeStatus to) {
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.canTransitionTo(to)) {
            throw new IllegalChargeTransitionException(from, to);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= LAST_ERROR_MAX) {
            return value;
        }
        return value.substring(0, LAST_ERROR_MAX);
    }
}
