package com.travelpay.payment.infra.persistence.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.payment.domain.transfer.Transfer;
import com.travelpay.payment.domain.transfer.TransferFailureReason;
import com.travelpay.payment.domain.transfer.TransferRepository;
import com.travelpay.payment.domain.transfer.TransferReviewReason;
import com.travelpay.payment.domain.transfer.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** 도메인 저장소를 Spring Data JPA 쿼리({@link TransferJpaRepository})로 구현합니다. 트랜잭션 설정은 쿼리 쪽을 따릅니다. */
@Repository
public class TransferRepositoryImpl implements TransferRepository {

    private final TransferJpaRepository transferJpaRepository;

    public TransferRepositoryImpl(TransferJpaRepository transferJpaRepository) {
        this.transferJpaRepository = transferJpaRepository;
    }

    @Override
    public Transfer saveAndFlush(Transfer transfer) {
        return transferJpaRepository.saveAndFlush(transfer);
    }

    @Override
    public Optional<Transfer> findById(UUID transferId) {
        return transferJpaRepository.findById(transferId);
    }

    @Override
    public Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, String idempotencyKey) {
        return transferJpaRepository.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey);
    }

    @Override
    public Page<Transfer> findByParticipant(UUID userId, boolean includeSent, boolean includeReceived,
            Pageable pageable) {
        return transferJpaRepository.findByParticipant(userId, includeSent, includeReceived, pageable);
    }

    @Override
    public int cancelRequested(UUID transferId, UUID senderId, Instant updatedAt) {
        return transferJpaRepository.cancelRequested(transferId, senderId, updatedAt);
    }

    @Override
    public int finishProcessing(UUID transferId, TransferStatus status, TransferFailureReason failureReason,
            Instant updatedAt) {
        return transferJpaRepository.finishProcessing(transferId, status, failureReason, updatedAt);
    }

    @Override
    public List<Transfer> findRecoveryTargets(Instant now, int limit) {
        return transferJpaRepository.findRecoveryTargets(now, limit);
    }

    @Override
    public int claimForRecovery(UUID transferId, Instant seenUpdatedAt, Instant now) {
        return transferJpaRepository.claimForRecovery(transferId, seenUpdatedAt, now);
    }

    @Override
    public int recordUnknownAttempt(UUID transferId, int expectedAttempts, TransferStatus status,
            TransferReviewReason reviewReason, Instant now) {
        return transferJpaRepository.recordUnknownAttempt(transferId, expectedAttempts, status, reviewReason, now);
    }

    @Override
    public int markManualReview(UUID transferId, TransferReviewReason reviewReason, Instant now) {
        return transferJpaRepository.markManualReview(transferId, reviewReason, now);
    }

    @Override
    public long countByStatus(TransferStatus status) {
        return transferJpaRepository.countByStatus(status);
    }

    @Override
    public long countProcessingCreatedBefore(Instant before) {
        return transferJpaRepository.countProcessingCreatedBefore(before);
    }

    @Override
    public List<UUID> findProcessingIdsCreatedBefore(Instant before, int limit) {
        return transferJpaRepository.findProcessingIdsCreatedBefore(before, PageRequest.of(0, limit));
    }
}
