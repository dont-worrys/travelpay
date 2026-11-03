package com.travelpay.payment.presentation.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.payment.application.transfer.dto.TransferResult;
import com.travelpay.payment.domain.transfer.TransferDirection;
import com.travelpay.payment.domain.transfer.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** 지갑 ID·멱등성 키 등 내부 식별 정보는 응답에 포함하지 않습니다. */
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
        @Schema(description = "메모", nullable = true)
        String memo,
        @Schema(description = "생성 시각(UTC, ISO-8601)", example = "2026-11-02T01:02:03.123456Z")
        Instant createdAt
) {

    public static TransferResponse from(TransferResult result) {
        return new TransferResponse(
                result.transferId(),
                result.direction(),
                result.senderId(),
                result.receiverId(),
                result.amount(),
                result.currency(),
                result.status(),
                result.memo(),
                result.createdAt()
        );
    }
}
