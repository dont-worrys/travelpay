package com.travelpay.payment.application.payment.client.ledger;

import com.travelpay.payment.domain.payment.ReviewLedgerStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * reference로 조회한 원장 거래 결과입니다. MANUAL_REVIEW 처리의 근거로 씁니다.
 * declineReason은 REJECTED일 때만 있으며, 원장 거절 사유를 결제·환불 문구로 바꾼 값입니다.
 * entries는 POSTED일 때 원장에 기록된 거래 항목이며, 우리 결제·환불 내용과 같은지 비교하는 데 씁니다.
 */
public record PaymentLedgerLookup(ReviewLedgerStatus status, UUID transactionId, String declineReason,
                                  List<Entry> entries) {

    public PaymentLedgerLookup {
        entries = List.copyOf(entries);
    }

    /** 원장 거래 항목 한 건. 비교할 때 이 값들이 모두 같아야 같은 항목입니다. */
    public record Entry(UUID walletId, String currency, String direction, long amount, String entryType) {
    }

    /** 원장 항목이 기대 항목과 같은 묶음인지(순서 무관, 개수까지 같아야 함) */
    public boolean hasSameEntries(List<Entry> expected) {
        if (entries.size() != expected.size()) {
            return false;
        }
        List<Entry> remaining = new ArrayList<>(entries);
        for (Entry entry : expected) {
            if (!remaining.remove(entry)) {
                return false;
            }
        }
        return true;
    }

    public static PaymentLedgerLookup posted(UUID transactionId, List<Entry> entries) {
        return new PaymentLedgerLookup(ReviewLedgerStatus.POSTED, transactionId, null, entries);
    }

    public static PaymentLedgerLookup rejected(UUID transactionId, String declineReason) {
        return new PaymentLedgerLookup(ReviewLedgerStatus.REJECTED, transactionId, declineReason, List.of());
    }

    public static PaymentLedgerLookup notFound() {
        return new PaymentLedgerLookup(ReviewLedgerStatus.NOT_FOUND, null, null, List.of());
    }
}
