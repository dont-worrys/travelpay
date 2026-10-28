package com.travelpay.payment.transfer.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
