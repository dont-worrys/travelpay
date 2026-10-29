package com.travelpay.payment.transfer.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

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
}
