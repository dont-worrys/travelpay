package com.travelpay.wallet.infra.persistence.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.charge.DuplicateChargeRequestException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ChargeRequestRepositoryImpl implements ChargeRequestRepository {

    private static final String IDEMPOTENCY_CONSTRAINT = "uk_charge_requests_idempotency";

    private final ChargeRequestJpaRepository jpaRepository;

    @Override
    public Optional<ChargeRequest> findById(UUID chargeId) {
        return jpaRepository.findById(chargeId);
    }

    @Override
    public Optional<ChargeRequest> findByUserIdAndIdempotencyKey(UUID userId, UUID idempotencyKey) {
        return jpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    /** flush로 멱등 키 UNIQUE 위반을 이 자리에서 드러낸다. 다른 무결성 위반은 그대로 던진다 */
    @Override
    public ChargeRequest save(ChargeRequest chargeRequest) {
        try {
            return jpaRepository.saveAndFlush(chargeRequest);
        } catch (DataIntegrityViolationException e) {
            if (isIdempotencyViolation(e)) {
                throw new DuplicateChargeRequestException(e);
            }
            throw e;
        }
    }

    @Override
    public int transitionStatus(UUID chargeId, ChargeStatus from, ChargeStatus to,
                                ChargeFailureCode failureCode, String lastError, Instant now) {
        return jpaRepository.transitionStatus(chargeId, from, to, failureCode, lastError, now);
    }

    @Override
    public int advanceStatus(UUID chargeId, ChargeStatus from, ChargeStatus to,
                             ChargeFailureCode failureCode, String lastError,
                             UUID bankTxId, UUID ledgerRef, UUID refundBankTxId, Instant now) {
        return jpaRepository.advanceStatus(chargeId, from, to, failureCode, lastError,
                bankTxId, ledgerRef, refundBankTxId, now);
    }

    @Override
    public OptionalInt increaseAttempt(UUID chargeId, ChargeStatus status, String lastError, Instant now) {
        if (jpaRepository.increaseAttempt(chargeId, status, lastError, now) == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(jpaRepository.findAttemptCount(chargeId));
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
