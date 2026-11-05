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
                - FAILED: 원장 REJECTED(실패 사유는 원장 거절 사유로 정함. 원장이 요청을 받지 않은 송금(LEDGER_REQUEST_NOT_ACCEPTED)은 NOT_PROCESSED)
                - PROCESSING: 원장 기록 없음, 자동 재처리로 되돌림""", example = "COMPLETED")
        @NotNull
        TransferStatus targetStatus,
        @Schema(description = "확정 판단 근거(이력에 저장). 원장 조회 결과(상태·거래 ID)와 거절 사유 등 나중에 판단을 다시 확인할 수 있는 내용",
                example = "원장 POSTED(c92b2030-1a2b-4c3d-8e9f-0123456789ab), 항목 일치 확인")
        @NotBlank
        @Size(max = 255)
        String reason,
        @Schema(description = "운영자가 확인한 원장 거래 ID. COMPLETED·FAILED일 때 필수, PROCESSING일 때는 보내지 않음",
                nullable = true)
        UUID ledgerTransactionId
) {
}
