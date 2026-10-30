package com.travelpay.payment.application.payment.client.ledger;

import java.util.UUID;

public record LedgerResult(Status status, UUID transactionId, String declineReason) {

    public enum Status {APPROVED, DECLINED, UNKNOWN}

    public static LedgerResult approved(UUID transactionId) {
        return new LedgerResult(Status.APPROVED, transactionId, null);
    }

    public static LedgerResult declined(String declineReason) {
        return new LedgerResult(Status.DECLINED, null, declineReason);
    }

    public static LedgerResult unknown() {
        return new LedgerResult(Status.UNKNOWN, null, null);
    }
}
