package com.travelpay.wallet.domain.charge;

import java.time.Instant;
import java.util.Optional;
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
}
