package com.travelpay.wallet.infra.persistence.resolution;

import com.travelpay.wallet.domain.resolution.ChargeResolution;
import com.travelpay.wallet.domain.resolution.ChargeResolutionRepository;
import com.travelpay.wallet.domain.resolution.DuplicateChargeResolutionException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ChargeResolutionRepositoryImpl implements ChargeResolutionRepository {

    private static final String IDEMPOTENCY_CONSTRAINT = "uk_charge_resolutions_idempotency";

    private final ChargeResolutionJpaRepository jpaRepository;

    /** flush로 멱등 키 UNIQUE 위반을 이 자리에서 드러낸다. 다른 무결성 위반은 그대로 던진다 */
    @Override
    public ChargeResolution save(ChargeResolution resolution) {
        try {
            return jpaRepository.saveAndFlush(resolution);
        } catch (DataIntegrityViolationException e) {
            if (isIdempotencyViolation(e)) {
                throw new DuplicateChargeResolutionException(e);
            }
            throw e;
        }
    }

    @Override
    public Optional<ChargeResolution> findByIdempotencyKey(UUID idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey);
    }

    private static boolean isIdempotencyViolation(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                return IDEMPOTENCY_CONSTRAINT.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
