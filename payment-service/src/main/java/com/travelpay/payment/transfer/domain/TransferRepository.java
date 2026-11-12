package com.travelpay.payment.transfer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 목록처럼 선택 조건이 많은 조회는 Specification(JpaSpecificationExecutor)으로 조건을 조합합니다. */
public interface TransferRepository extends JpaRepository<Transfer, UUID>, JpaSpecificationExecutor<Transfer> {

    Optional<Transfer> findBySenderIdAndIdempotencyKey(UUID senderId, String idempotencyKey);

    /** 회원이 보내거나 받은 송금을 조회합니다. 두 플래그로 보낸·받은 내역 포함 여부를 정하고, 정렬은 pageable을 따릅니다. */
    @Query("""
            select t from Transfer t
            where (:includeSent = true and t.senderId = :userId)
               or (:includeReceived = true and t.receiverId = :userId)
            """)
    Page<Transfer> findByParticipant(UUID userId, boolean includeSent, boolean includeReceived, Pageable pageable);

    /**
     * 송금인의 접수 상태 송금만 취소하고 변경된 행 수(0 또는 1)를 반환합니다.
     * 벌크 UPDATE는 @PreUpdate가 실행되지 않으므로 수정 시각을 직접 받으며, 이후 조회가 새 상태를 읽도록 영속성 컨텍스트를 비웁니다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transfer t
            set t.status = com.travelpay.payment.transfer.domain.TransferStatus.CANCELLED, t.updatedAt = :updatedAt
            where t.id = :transferId
              and t.senderId = :senderId
              and t.status = com.travelpay.payment.transfer.domain.TransferStatus.REQUESTED
            """)
    int cancelRequested(UUID transferId, UUID senderId, Instant updatedAt);

    /**
     * 처리 중(PROCESSING) 송금만 처리 결과 상태로 바꾸고 변경된 행 수(0 또는 1)를 반환합니다.
     * 실패 사유는 FAILED일 때만 넣고 그 외에는 null입니다(ck_transfer_failure_reason).
     * 같은 송금의 재요청이 동시에 결과를 반영해도 한 번만 바뀌며, 원장 호출 후 짧게 실행되도록 자체 트랜잭션으로 커밋합니다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transfer t
            set t.status = :status, t.failureReason = :failureReason, t.updatedAt = :updatedAt
            where t.id = :transferId
              and t.status = com.travelpay.payment.transfer.domain.TransferStatus.PROCESSING
            """)
    int finishProcessing(UUID transferId, TransferStatus status, TransferFailureReason failureReason, Instant updatedAt);

    /**
     * 복구 대상: 처리 중이고 재시도 대기 시간(30초 × 2^max(attempt_count - 1, 0))이 지난 송금을 수정 시각 오래된 순으로 조회합니다.
     * 지갑 충전 복구와 같은 간격(30초 → 1분 → 2분 → 4분)입니다. 첫 updated_at 조건은 대기 시간의 하한으로,
     * 부분 인덱스(ix_transfer_processing_updated_at) 범위 스캔을 쓰게 하려는 중복 조건입니다.
     */
    @Query(nativeQuery = true, value = """
            select * from transfer
            where status = 'PROCESSING'
              and updated_at < cast(:now as timestamptz) - interval '30 seconds'
              and updated_at < cast(:now as timestamptz)
                  - interval '30 seconds' * power(2, greatest(attempt_count - 1, 0))
            order by updated_at, id
            limit :limit
            """)
    List<Transfer> findRecoveryTargets(Instant now, int limit);

    /**
     * 복구 선점: 조회 이후 아무도 바꾸지 않았을 때(상태·수정 시각이 그대로)만 수정 시각을 갱신하고 변경된 행 수를 반환합니다.
     * 여러 인스턴스가 같은 송금을 동시에 다시 요청하지 않게 합니다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transfer t
            set t.updatedAt = :now
            where t.id = :transferId
              and t.status = com.travelpay.payment.transfer.domain.TransferStatus.PROCESSING
              and t.updatedAt = :seenUpdatedAt
            """)
    int claimForRecovery(UUID transferId, Instant seenUpdatedAt, Instant now);

    /**
     * 결과 모름 기록: 처리 중이고 시도 횟수가 기대값일 때만 횟수를 1 늘리고 상태를 바꿉니다.
     * 한도에 이르면 status=MANUAL_REVIEW·reviewReason=RETRY_EXHAUSTED, 아니면 PROCESSING·null을 넘깁니다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transfer t
            set t.attemptCount = :expectedAttempts + 1, t.status = :status, t.reviewReason = :reviewReason,
                t.updatedAt = :now
            where t.id = :transferId
              and t.status = com.travelpay.payment.transfer.domain.TransferStatus.PROCESSING
              and t.attemptCount = :expectedAttempts
            """)
    int recordUnknownAttempt(UUID transferId, int expectedAttempts, TransferStatus status,
            TransferReviewReason reviewReason, Instant now);

    /** 처리 중 송금의 자동 처리를 멈추고 MANUAL_REVIEW로 바꿉니다. 그 사이 결과가 반영된 송금은 바꾸지 않습니다. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transfer t
            set t.status = com.travelpay.payment.transfer.domain.TransferStatus.MANUAL_REVIEW,
                t.reviewReason = :reviewReason, t.updatedAt = :now
            where t.id = :transferId
              and t.status = com.travelpay.payment.transfer.domain.TransferStatus.PROCESSING
            """)
    int markManualReview(UUID transferId, TransferReviewReason reviewReason, Instant now);

    /**
     * 운영자 확정: MANUAL_REVIEW 송금만 지정 상태로 바꾸고 변경된 행 수(0 또는 1)를 반환합니다. 검토 사유는 비웁니다.
     * 실패 사유는 FAILED일 때만 넣고(ck_transfer_failure_reason), 재처리(PROCESSING)로 돌릴 때는 시도 횟수를 0으로 넘깁니다.
     * 이력 저장과 같은 트랜잭션에서 실행되도록 자체 트랜잭션을 두지 않습니다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transfer t
            set t.status = :status, t.failureReason = :failureReason, t.reviewReason = null,
                t.attemptCount = :attemptCount, t.updatedAt = :now
            where t.id = :transferId
              and t.status = com.travelpay.payment.transfer.domain.TransferStatus.MANUAL_REVIEW
            """)
    int resolveManualReview(UUID transferId, TransferStatus status, TransferFailureReason failureReason,
            int attemptCount, Instant now);

    long countByStatus(TransferStatus status);

    /**
     * 자동 대사 대상: MANUAL_REVIEW로 바뀐 지 기준 시각이 지난 송금을 (수정 시각, ID) 순으로 커서 다음부터 조회합니다.
     * 확정하지 못하고 남는 송금이 있어도 매번 같은 앞쪽 건만 보지 않도록 커서로 이어서 돌고, 끝까지 가면 처음부터 다시 봅니다.
     */
    @Query(nativeQuery = true, value = """
            select * from transfer
            where status = 'MANUAL_REVIEW'
              and updated_at < cast(:before as timestamptz)
              and (updated_at, id) > (cast(:afterUpdatedAt as timestamptz), cast(:afterId as uuid))
            order by updated_at, id
            limit :limit
            """)
    List<Transfer> findManualReviewAfter(Instant before, Instant afterUpdatedAt, UUID afterId, int limit);

    /** 생성 시각이 기준 시각 이전인데 아직 처리 중인 송금 수입니다. 오래 확정되지 않은 송금 알림에 씁니다. */
    @Query("""
            select count(t) from Transfer t
            where t.status = com.travelpay.payment.transfer.domain.TransferStatus.PROCESSING
              and t.createdAt < :before
            """)
    long countProcessingCreatedBefore(Instant before);

    /** 생성 시각이 기준 시각 이전인데 아직 처리 중인 송금 ID를 오래된 순으로 조회합니다. */
    @Query("""
            select t.id from Transfer t
            where t.status = com.travelpay.payment.transfer.domain.TransferStatus.PROCESSING
              and t.createdAt < :before
            order by t.createdAt asc, t.id asc
            """)
    List<UUID> findProcessingIdsCreatedBefore(Instant before, Pageable pageable);
}
