package com.travelpay.payment.transfer.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferStatusHistoryRepository extends JpaRepository<TransferStatusHistory, UUID> {

    /** 송금의 상태 변경 이력을 기록 순서(생성 시각, 같으면 ID)대로 조회합니다. */
    List<TransferStatusHistory> findByTransferIdOrderByCreatedAtAscIdAsc(UUID transferId);
}
