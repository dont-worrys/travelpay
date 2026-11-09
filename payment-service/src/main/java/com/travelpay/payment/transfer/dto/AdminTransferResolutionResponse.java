package com.travelpay.payment.transfer.dto;

import java.util.UUID;

import com.travelpay.payment.transfer.client.LedgerTransactionResponse;
import com.travelpay.payment.transfer.domain.TransferFailureReason;
import com.travelpay.payment.transfer.domain.TransferReviewReason;
import com.travelpay.payment.transfer.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MANUAL_REVIEW 송금 확정 미리보기 응답입니다. 서버가 조회한 원장 거래 결과와 그 결과로 확정할 상태를 담습니다.
 * resolvable이 true이면 expectedStatus·ledgerTransactionId를 그대로 확정 요청(resolve)에 넣으면 됩니다.
 */
public record AdminTransferResolutionResponse(
        @Schema(description = "송금 ID")
        UUID transferId,
        @Schema(description = "MANUAL_REVIEW로 전환된 사유. RETRY_EXHAUSTED 결과 모름 한도 도달, "
                + "LEDGER_REQUEST_NOT_ACCEPTED 원장이 요청을 받지 않음(409·400 등)")
        TransferReviewReason reviewReason,
        @Schema(description = "서버가 reference(TRANSFER + 송금 ID)로 조회한 원장 거래 결과")
        Ledger ledger,
        @Schema(description = "확정할 수 있는지. 원장 POSTED인데 항목이 송금과 다르면 false")
        boolean resolvable,
        @Schema(description = """
                확정할 상태(확정할 수 없으면 없음)
                - COMPLETED: 원장 POSTED, 항목 일치
                - FAILED: 원장 REJECTED
                - PROCESSING: 원장 기록 없음, 자동 재처리로 되돌림""", nullable = true, example = "COMPLETED")
        TransferStatus expectedStatus,
        @Schema(description = "FAILED로 확정할 때의 실패 사유. 원장 거절 사유로 정하며, "
                + "원장이 요청을 받지 않은 송금은 NOT_PROCESSED", nullable = true)
        TransferFailureReason expectedFailureReason,
        @Schema(description = "확정 요청에 넣을 원장 거래 ID. COMPLETED·FAILED일 때만 있음", nullable = true)
        UUID ledgerTransactionId
) {

    /** 원장 거래 결과입니다. 원장 기록이 없으면 found=false이고 나머지는 없습니다. */
    public record Ledger(
            @Schema(description = "원장 거래가 있는지")
            boolean found,
            @Schema(description = "원장 거래 ID", nullable = true)
            UUID transactionId,
            @Schema(description = "원장 거래 상태", nullable = true)
            LedgerTransactionResponse.Status status,
            @Schema(description = "원장 거절 사유. REJECTED일 때만 있음", nullable = true)
            LedgerTransactionResponse.RejectReason rejectReason,
            @Schema(description = "원장 항목(지갑·통화·방향·금액·entryType)이 송금과 같은지. POSTED일 때만 있음", nullable = true)
            Boolean entriesMatch
    ) {
    }
}
