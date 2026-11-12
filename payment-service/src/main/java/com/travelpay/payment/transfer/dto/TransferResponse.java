package com.travelpay.payment.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferDirection;
import com.travelpay.payment.transfer.domain.TransferFailureReason;
import com.travelpay.payment.transfer.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 지갑 ID·멱등성 키 등 내부 식별 정보는 응답에 포함하지 않습니다.
 * 실패 사유는 사용자용 값만 노출하며(송금 요청 422 오류 코드와 같은 이름), 운영용 검토 사유(reviewReason)는 노출하지 않습니다.
 */
public record TransferResponse(
        @Schema(description = "송금 ID")
        UUID transferId,
        @Schema(description = "요청자 기준 방향. SENT 보낸 송금, RECEIVED 받은 송금")
        TransferDirection direction,
        @Schema(description = "송금인 회원 ID")
        UUID senderId,
        @Schema(description = "수취인 회원 ID")
        UUID receiverId,
        @Schema(description = "송금 금액(통화별 최소 단위)", example = "10000")
        long amount,
        @Schema(description = "통화 코드", example = "KRW")
        String currency,
        @Schema(description = """
                송금 상태
                - REQUESTED: 접수(원장 연동 전 생성된 송금, 취소 가능)
                - PROCESSING: 원장 처리 결과 미확정(서버가 자동 재처리)
                - COMPLETED: 자금 이동 완료
                - FAILED: 원장 거절(잔액 부족·계정 사용 불가)
                - CANCELLED: 취소됨
                - MANUAL_REVIEW: 자동 처리 중단, 운영자 확인 필요""")
        TransferStatus status,
        @Schema(description = """
                실패 사유. FAILED일 때만 값이 있고 그 밖의 상태는 null
                - INSUFFICIENT_BALANCE: 송금인 잔액 부족
                - ACCOUNT_UNAVAILABLE: 송금인 또는 수취인 계정을 쓸 수 없음
                - NOT_PROCESSED: 처리되지 않음(자금 이동 없음)""", nullable = true)
        TransferFailureReason failureReason,
        @Schema(description = "메모", nullable = true)
        String memo,
        @Schema(description = "생성 시각(UTC, ISO-8601)", example = "2026-11-02T01:02:03.123456Z")
        Instant createdAt
) {

    /** viewerId는 송금 당사자여야 하며, 당사자 확인은 호출자가 먼저 수행합니다. */
    public static TransferResponse of(Transfer transfer, UUID viewerId) {
        TransferDirection direction = transfer.getSenderId().equals(viewerId)
                ? TransferDirection.SENT : TransferDirection.RECEIVED;
        return new TransferResponse(
                transfer.getId(),
                direction,
                transfer.getSenderId(),
                transfer.getReceiverId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getStatus() == TransferStatus.FAILED ? transfer.getFailureReason() : null,
                transfer.getMemo(),
                transfer.getCreatedAt()
        );
    }
}
