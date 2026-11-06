package com.travelpay.wallet.domain.resolution;

import java.util.Optional;
import java.util.UUID;

public interface ChargeResolutionRepository {

    /** 바로 flush한다. 멱등 키 UNIQUE 위반이면 DuplicateChargeResolutionException, 다른 무결성 위반은 그대로 던진다 */
    ChargeResolution save(ChargeResolution resolution);

    Optional<ChargeResolution> findByIdempotencyKey(UUID idempotencyKey);
}
