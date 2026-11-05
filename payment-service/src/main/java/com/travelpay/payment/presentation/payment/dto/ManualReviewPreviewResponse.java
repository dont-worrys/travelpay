package com.travelpay.payment.presentation.payment.dto;

import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.application.payment.dto.ManualReviewPreview;

import java.util.UUID;

/** 단건 조회 응답의 원장 조회 결과(ledger)와 예상 처리 결과(expected). 원장 조회에 실패하면 둘 다 null */
public final class ManualReviewPreviewResponse {

    /** declineReason은 원장이 거절했을 때만 있습니다 */
    public record Ledger(String status, UUID transactionId, String declineReason) {
        static Ledger from(PaymentLedgerLookup lookup) {
            return lookup == null ? null
                    : new Ledger(lookup.status().name(), lookup.transactionId(), lookup.declineReason());
        }
    }

    /** resolvable이 false면 원장 보정이 필요해 처리할 수 없습니다. 그때 status·paymentStatus는 null */
    public record Expected(boolean resolvable, String status, String paymentStatus, String description) {
        static Expected from(ManualReviewPreview.Expected expected) {
            return expected == null ? null
                    : new Expected(expected.resolvable(), expected.status(),
                    expected.paymentStatus() == null ? null : expected.paymentStatus().name(),
                    expected.description());
        }
    }

    private ManualReviewPreviewResponse() {
    }

    static Ledger ledger(ManualReviewPreview preview) {
        return Ledger.from(preview.ledger());
    }

    static Expected expected(ManualReviewPreview preview) {
        return Expected.from(preview.expected());
    }
}
