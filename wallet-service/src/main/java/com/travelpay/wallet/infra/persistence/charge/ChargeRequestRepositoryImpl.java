package com.travelpay.wallet.infra.persistence.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.charge.DuplicateChargeRequestException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class ChargeRequestRepositoryImpl implements ChargeRequestRepository {

    private static final String IDEMPOTENCY_CONSTRAINT = "uk_charge_requests_idempotency";
    private static final int IN_CHUNK = 1000;

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

    @Override
    public List<ChargeRecoveryTarget> findRecoveryTargets(int limit) {
        return jpaRepository.findRecoveryTargets(limit).stream()
                .map(row -> new ChargeRecoveryTarget(row.getId(), ChargeStatus.valueOf(row.getStatus()),
                        row.getUpdatedAt()))
                .toList();
    }

    @Override
    public int claimForRecovery(UUID chargeId, ChargeStatus status, Instant updatedAt) {
        return jpaRepository.claimForRecovery(chargeId, status.name(), updatedAt);
    }

    @Override
    public Page<ChargeRequest> findByUser(UUID userId, ChargeStatus status, Instant from, Instant to,
                                          Pageable pageable) {
        return jpaRepository.findByUser(userId, status, from, to, pageable);
    }

    @Override
    public Page<ChargeRequest> search(UUID userId, ChargeStatus status, Instant from, Instant to,
                                      Pageable pageable) {
        return jpaRepository.search(userId, status, from, to, pageable);
    }

    @Override
    public List<ChargeRequest> findCreatedBetween(Instant from, Instant to) {
        return jpaRepository.findCreatedBetween(from, to);
    }

    @Override
    public List<ChargeRequest> findByBankOrRefundRequestIds(Collection<String> requestIds) {
        return inChunks(requestIds, jpaRepository::findByBankOrRefundRequestIds);
    }

    @Override
    public List<ChargeRequest> findAllByIds(Collection<UUID> chargeIds) {
        return inChunks(chargeIds, jpaRepository::findAllById);
    }

    /** IN 목록을 나눠 조회한다 (바인드 파라미터 수 제한 회피). 빈 목록이면 조회하지 않는다 */
    private static <K> List<ChargeRequest> inChunks(Collection<K> keys,
                                                    Function<List<K>, List<ChargeRequest>> query) {
        List<K> all = List.copyOf(keys);
        List<ChargeRequest> found = new ArrayList<>();
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            found.addAll(query.apply(all.subList(i, Math.min(i + IN_CHUNK, all.size()))));
        }
        return found;
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
