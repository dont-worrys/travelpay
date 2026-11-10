package com.travelpay.payment.transfer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferStatusHistoryRepository extends JpaRepository<TransferStatusHistory, UUID> {

    /** 송금의 상태 변경 이력을 기록 순서(생성 시각, 같으면 ID)대로 조회합니다. */
    List<TransferStatusHistory> findByTransferIdOrderByCreatedAtAscIdAsc(UUID transferId);

    /** 송금의 마지막 이력 중 지정한 주체의 것을 조회합니다. 확정 요청의 재시도 판단에 씁니다. */
    Optional<TransferStatusHistory> findFirstByTransferIdAndActorTypeOrderByCreatedAtDescIdDesc(
            UUID transferId, TransferStatusHistory.ActorType actorType);
}
