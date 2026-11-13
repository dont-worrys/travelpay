package com.travelpay.payment.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.domain.TransferStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;

public record AdminTransferHistoryResponse(
        @Schema(description = "이력 ID")
        UUID historyId,
        @Schema(description = "변경 전 상태")
        TransferStatus fromStatus,
        @Schema(description = "변경 후 상태")
        TransferStatus toStatus,
        @Schema(description = "변경 사유")
        String reason,
        @Schema(description = "변경 주체. OPERATOR 운영자, SYSTEM MANUAL_REVIEW 자동 대사")
        TransferStatusHistory.ActorType actorType,
        @Schema(description = "변경한 운영자 회원 ID. SYSTEM이면 없음", nullable = true)
        UUID actorId,
        @Schema(description = "확정 근거 원장 거래 ID. 재처리로 돌린 경우 없음", nullable = true)
        UUID ledgerTransactionId,
        @Schema(description = "기록 시각(UTC, ISO-8601)")
        Instant createdAt
) {

    public static AdminTransferHistoryResponse from(TransferStatusHistory history) {
        return new AdminTransferHistoryResponse(
                history.getId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getReason(),
                history.getActorType(),
                history.getActorId(),
                history.getLedgerTransactionId(),
                history.getCreatedAt()
        );
    }
}
