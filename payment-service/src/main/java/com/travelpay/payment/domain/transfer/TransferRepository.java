package com.travelpay.payment.domain.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 송금 저장소입니다. 구현과 쿼리는 infra.persistence.transfer에 있습니다.
 * 조건부 UPDATE 메서드는 변경된 행 수(0 또는 1)를 반환하며, 0이면 다른 곳에서 먼저 상태를 바꾼 것입니다.
 */
public interface TransferRepository {

    /** 저장 후 바로 flush해, UNIQUE(sender_id, idempotency_key) 위반을 호출한 쪽에서 잡을 수 있게 합니다. */
    Transfer saveAndFlush(Transfer transfer);

    Optional<Transfer> findById(UUID transferId);

    Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, String idempotencyKey);

    /** 회원이 보내거나 받은 송금을 조회합니다. 두 플래그로 보낸·받은 내역 포함 여부를 정하고, 정렬은 pageable을 따릅니다. */
    Page<Transfer> findByParticipant(UUID userId, boolean includeSent, boolean includeReceived, Pageable pageable);

    /** 송금인의 접수(REQUESTED) 상태 송금만 취소합니다. 호출한 쪽의 트랜잭션 안에서 실행합니다. */
    int cancelRequested(UUID transferId, UUID senderId, Instant updatedAt);

    /** 처리 중(PROCESSING) 송금만 처리 결과 상태로 바꿉니다. 실패 사유는 FAILED일 때만 넣습니다. 자체 트랜잭션으로 커밋합니다. */
    int finishProcessing(UUID transferId, TransferStatus status, TransferFailureReason failureReason, Instant updatedAt);

    /** 재시도 대기 시간이 지난 처리 중 송금을 수정 시각 오래된 순으로 최대 limit건 조회합니다. */
    List<Transfer> findRecoveryTargets(Instant now, int limit);

    /** 조회 이후 상태·수정 시각이 그대로일 때만 수정 시각을 갱신해 복구 대상으로 선점합니다. */
    int claimForRecovery(UUID transferId, Instant seenUpdatedAt, Instant now);

    /** 처리 중이고 시도 횟수가 기대값일 때만 횟수를 1 늘리고 상태(PROCESSING 또는 MANUAL_REVIEW)를 바꿉니다. */
    int recordUnknownAttempt(UUID transferId, int expectedAttempts, TransferStatus status,
            TransferReviewReason reviewReason, Instant now);

    /** 처리 중 송금의 자동 처리를 멈추고 MANUAL_REVIEW로 바꿉니다. */
    int markManualReview(UUID transferId, TransferReviewReason reviewReason, Instant now);

    long countByStatus(TransferStatus status);

    /** 생성 시각이 기준 시각 이전인데 아직 처리 중인 송금 수입니다. */
    long countProcessingCreatedBefore(Instant before);

    /** 생성 시각이 기준 시각 이전인데 아직 처리 중인 송금 ID를 오래된 순으로 최대 limit건 조회합니다. */
    List<UUID> findProcessingIdsCreatedBefore(Instant before, int limit);
}
