package com.travelpay.wallet.domain.charge;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public interface ChargeRequestRepository {

    Optional<ChargeRequest> findById(UUID chargeId);

    Optional<ChargeRequest> findByUserIdAndIdempotencyKey(UUID userId, UUID idempotencyKey);

    /** 바로 flush한다. 멱등 키 UNIQUE 위반이면 DuplicateChargeRequestException, 다른 무결성 위반은 그대로 던진다 */
    ChargeRequest save(ChargeRequest chargeRequest);

    /**
     * 현재 상태가 from일 때만 to로 바꾸고 updated_at을 갱신한다. 반환값은 바뀐 행 수(0 또는 1).
     * failureCode·lastError가 null이면 기존 값을 유지한다. 트랜잭션 안에서만 사용
     */
    int transitionStatus(UUID chargeId, ChargeStatus from, ChargeStatus to,
                         ChargeFailureCode failureCode, String lastError, Instant now);

    /**
     * 다음 단계로 전이: transitionStatus에 더해 bank_tx_id·ledger_ref·refund_bank_tx_id를 함께 저장하고
     * attempt_count를 0으로 초기화한다. null인 값은 기존 값을 유지한다. 트랜잭션 안에서만 사용
     */
    int advanceStatus(UUID chargeId, ChargeStatus from, ChargeStatus to,
                      ChargeFailureCode failureCode, String lastError,
                      UUID bankTxId, UUID ledgerRef, UUID refundBankTxId, Instant now);

    /**
     * 결과 모름 기록: 현재 상태가 status일 때만 attempt_count +1, last_error 갱신.
     * 바뀐 뒤의 attempt_count를 돌려주고, 상태가 달라 바꾸지 않았으면 empty. 트랜잭션 안에서만 사용
     */
    OptionalInt increaseAttempt(UUID chargeId, ChargeStatus status, String lastError, Instant now);
}
