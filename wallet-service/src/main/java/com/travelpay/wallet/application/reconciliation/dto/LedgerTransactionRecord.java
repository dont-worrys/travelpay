package com.travelpay.wallet.application.reconciliation.dto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 대사에 쓰는 원장 충전 거래 사실 (referenceType = CHARGE). REJECTED 거래는 entries가 비어 있다 */
public record LedgerTransactionRecord(UUID transactionId, String status, UUID referenceId,
                                      List<LedgerEntryRecord> entries) {

    private static final String POSTED = "POSTED";
    private static final String USER = "USER";
    private static final String CREDIT = "CREDIT";
    private static final String CHARGE_IN = "CHARGE_IN";

    public LedgerTransactionRecord {
        entries = List.copyOf(entries);
    }

    public boolean isPosted() {
        return POSTED.equals(status);
    }

    /** 지갑 적립 항목: USER·CREDIT·CHARGE_IN이고 walletId가 충전의 지갑인 첫 항목 */
    public Optional<LedgerEntryRecord> chargeInEntry(UUID walletId) {
        return entries.stream()
                .filter(e -> USER.equals(e.ownerType()) && CREDIT.equals(e.direction())
                        && CHARGE_IN.equals(e.entryType()) && walletId.equals(e.walletId()))
                .findFirst();
    }

    public record LedgerEntryRecord(String ownerType, UUID walletId, String direction, String currency,
                                    long amount, String entryType) {
    }
}
