package com.travelpay.payment.transfer.application;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.payment.transfer.client.LedgerTransactionResponse;
import com.travelpay.payment.transfer.client.LedgerTransferRecord;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferFailureReason;
import com.travelpay.payment.transfer.domain.TransferReviewReason;
import com.travelpay.payment.transfer.domain.TransferStatus;

/**
 * MANUAL_REVIEW 송금을 원장 거래 결과로 확정할 때의 판단 결과입니다. 확정 미리보기와 실제 확정({@link TransferAdminService})이 같은 판단을 씁니다.
 * <ul>
 *     <li>원장 기록 없음 → PROCESSING(자동 재처리)</li>
 *     <li>원장 POSTED + 항목이 송금과 같음 → COMPLETED. 항목이 다르면 같은 reference로 다른 내용이 기록된 것이라 확정 불가</li>
 *     <li>원장 REJECTED → FAILED. 실패 사유는 원장 거절 사유이며, 원장이 요청을 받지 않은 송금(LEDGER_REQUEST_NOT_ACCEPTED)은
 *         거절 기록이 이전의 다른 요청 것일 수 있어 NOT_PROCESSED</li>
 * </ul>
 *
 * @param ledger         원장 reference 조회 결과(없으면 empty)
 * @param entriesMatch   원장 POSTED일 때 원장 항목이 송금과 같은지. POSTED가 아니면 null
 * @param expectedStatus 확정할 상태. 확정할 수 없으면 null
 * @param failureReason  FAILED로 확정할 때의 실패 사유, 그 밖에는 null
 */
public record TransferResolution(Optional<LedgerTransferRecord> ledger, Boolean entriesMatch,
        TransferStatus expectedStatus, TransferFailureReason failureReason) {

    /** 송금과 원장 조회 결과로 확정할 상태를 정합니다. 상태·이력을 바꾸지 않습니다. */
    public static TransferResolution decide(Transfer transfer, Optional<LedgerTransferRecord> ledger) {
        if (ledger.isEmpty()) {
            return new TransferResolution(ledger, null, TransferStatus.PROCESSING, null);
        }
        LedgerTransactionResponse result = ledger.get().result();
        if (result.status() == LedgerTransactionResponse.Status.POSTED) {
            boolean matches = ledger.get().matches(transfer);
            return new TransferResolution(ledger, matches, matches ? TransferStatus.COMPLETED : null, null);
        }
        TransferFailureReason failureReason;
        if (transfer.getReviewReason() == TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED) {
            failureReason = TransferFailureReason.NOT_PROCESSED;
        } else if (result.rejectReason() == LedgerTransactionResponse.RejectReason.INSUFFICIENT_BALANCE) {
            failureReason = TransferFailureReason.INSUFFICIENT_BALANCE;
        } else {
            failureReason = TransferFailureReason.ACCOUNT_UNAVAILABLE;
        }
        return new TransferResolution(ledger, null, TransferStatus.FAILED, failureReason);
    }

    /** 확정할 수 있는지 여부입니다. */
    public boolean resolvable() {
        return expectedStatus != null;
    }

    /** 원장 거래 ID입니다. 원장 기록이 없으면 null입니다. */
    public UUID ledgerTransactionId() {
        return ledger.map(record -> record.result().transactionId()).orElse(null);
    }

    /**
     * 확정 요청에 넣어야 하는 ledgerTransactionId입니다. COMPLETED·FAILED는 원장 거래 ID, PROCESSING과 확정 불가는 null입니다.
     */
    public UUID expectedLedgerTransactionId() {
        return expectedStatus == null || expectedStatus == TransferStatus.PROCESSING ? null : ledgerTransactionId();
    }
}
