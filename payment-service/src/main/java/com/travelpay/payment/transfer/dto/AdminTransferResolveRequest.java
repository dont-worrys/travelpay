package com.travelpay.payment.transfer.dto;

import java.util.UUID;

import com.travelpay.payment.transfer.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 운영자의 MANUAL_REVIEW 송금 확정 요청입니다. 서버가 원장 거래 결과를 직접 조회해 targetStatus·ledgerTransactionId와 맞는지 확인합니다.
 */
public record AdminTransferResolveRequest(
        @Schema(description = """
                확정할 상태
                - COMPLETED: 원장 POSTED
                - FAILED: 원장 REJECTED(실패 사유는 원장 거절 사유로 정함)
                - PROCESSING: 원장 기록 없음, 자동 재처리로 되돌림""", example = "COMPLETED")
        @NotNull
        TransferStatus targetStatus,
        @Schema(description = "확정 사유(운영 기록용)", example = "원장 reference 조회 결과 POSTED 확인")
        @NotBlank
        @Size(max = 255)
        String reason,
        @Schema(description = "운영자가 확인한 원장 거래 ID. COMPLETED·FAILED일 때 필수, PROCESSING일 때는 보내지 않음",
                nullable = true)
        UUID ledgerTransactionId
) {
}
