package com.travelpay.payment.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferFailureReason;
import com.travelpay.payment.transfer.domain.TransferReviewReason;
import com.travelpay.payment.transfer.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자(MASTER)용 송금 응답입니다. 운영 확인에 필요한 지갑 ID·실패/검토 사유·시도 횟수·수정 시각을 포함합니다.
 * 멱등성 키·요청 해시는 클라이언트 내부 값이라 관리자에게도 내보내지 않으며, 요청자 기준 방향(direction)은 없습니다.
 */
public record AdminTransferResponse(
        @Schema(description = "송금 ID")
        UUID transferId,
        @Schema(description = "송금인 회원 ID")
        UUID senderId,
        @Schema(description = "수취인 회원 ID")
        UUID receiverId,
        @Schema(description = "송금인 지갑 ID")
        UUID sourceWalletId,
        @Schema(description = "수취인 지갑 ID")
        UUID targetWalletId,
        @Schema(description = "송금 금액(통화별 최소 단위)", example = "10000")
        long amount,
        @Schema(description = "통화 코드", example = "KRW")
        String currency,
        @Schema(description = "송금 상태")
        TransferStatus status,
        @Schema(description = "원장 거절 사유. FAILED일 때만 있음", nullable = true)
        TransferFailureReason failureReason,
        @Schema(description = "자동 처리 중단 사유. MANUAL_REVIEW일 때만 있음", nullable = true)
        TransferReviewReason reviewReason,
        @Schema(description = "원장 처리 결과를 확인하지 못한 횟수", example = "0")
        int attemptCount,
        @Schema(description = "메모", nullable = true)
        String memo,
        @Schema(description = "생성 시각(UTC, ISO-8601)", example = "2026-11-02T01:02:03.123456Z")
        Instant createdAt,
        @Schema(description = "마지막 수정 시각(UTC, ISO-8601)", example = "2026-11-02T01:02:04.123456Z")
        Instant updatedAt
) {

    public static AdminTransferResponse from(Transfer transfer) {
        return new AdminTransferResponse(
                transfer.getId(),
                transfer.getSenderId(),
                transfer.getReceiverId(),
                transfer.getSourceWalletId(),
                transfer.getTargetWalletId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getFailureReason(),
                transfer.getReviewReason(),
                transfer.getAttemptCount(),
                transfer.getMemo(),
                transfer.getCreatedAt(),
                transfer.getUpdatedAt()
        );
    }
}
