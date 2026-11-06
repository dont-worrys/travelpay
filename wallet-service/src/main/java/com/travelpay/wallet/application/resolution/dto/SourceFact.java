package com.travelpay.wallet.application.resolution.dto;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.resolution.ResolutionEvidence;

import java.util.UUID;

/**
 * 한 소스(은행 출금·환불·원장)의 사실. lookup: FOUND·NOT_FOUND·UNAVAILABLE.
 * 원장 amount·currency는 충전 지갑 적립 항목(USER·CREDIT·CHARGE_IN) 값이다. 은행은 currency가 없다
 */
public record SourceFact(String lookup, UUID transactionId, String status, Long amount, String currency) {

    public static final String UNAVAILABLE = "UNAVAILABLE";

    public static SourceFact ofBank(SourceLookup<BankTransactionRecord> lookup) {
        return switch (lookup) {
            case SourceLookup.Found<BankTransactionRecord> found -> new SourceFact(ResolutionEvidence.FOUND,
                    found.record().bankTxId(), found.record().status(), found.record().amount(), null);
            case SourceLookup.NotFound<BankTransactionRecord> ignored -> notFound();
            case SourceLookup.Unavailable<BankTransactionRecord> ignored -> unavailable();
        };
    }

    public static SourceFact ofLedger(SourceLookup<LedgerTransactionRecord> lookup, UUID walletId) {
        return switch (lookup) {
            case SourceLookup.Found<LedgerTransactionRecord> found -> {
                LedgerTransactionRecord record = found.record();
                var entry = record.chargeInEntry(walletId);
                yield new SourceFact(ResolutionEvidence.FOUND, record.transactionId(), record.status(),
                        entry.map(LedgerTransactionRecord.LedgerEntryRecord::amount).orElse(null),
                        entry.map(LedgerTransactionRecord.LedgerEntryRecord::currency).orElse(null));
            }
            case SourceLookup.NotFound<LedgerTransactionRecord> ignored -> notFound();
            case SourceLookup.Unavailable<LedgerTransactionRecord> ignored -> unavailable();
        };
    }

    /** 저장된 보정 증거 (조회 불가로는 보정하지 않으므로 FOUND·NOT_FOUND만 있다) */
    public static SourceFact ofEvidence(ResolutionEvidence evidence) {
        return new SourceFact(evidence.getLookup(), evidence.getTransactionId(), evidence.getStatus(),
                evidence.getAmount(), null);
    }

    public ResolutionEvidence toEvidence() {
        if (UNAVAILABLE.equals(lookup)) {
            throw new IllegalStateException("조회 불가 사실은 보정 증거가 될 수 없습니다.");
        }
        return ResolutionEvidence.FOUND.equals(lookup)
                ? ResolutionEvidence.found(transactionId, status, amount)
                : ResolutionEvidence.notFound();
    }

    public boolean isUnavailable() {
        return UNAVAILABLE.equals(lookup);
    }

    /** 거절 응답·로그용 한 줄 요약 */
    public String summary() {
        if (!ResolutionEvidence.FOUND.equals(lookup)) {
            return lookup;
        }
        return lookup + " status=" + status + " amount=" + amount + (currency != null ? " " + currency : "")
                + " txId=" + transactionId;
    }

    private static SourceFact notFound() {
        return new SourceFact(ResolutionEvidence.NOT_FOUND, null, null, null, null);
    }

    private static SourceFact unavailable() {
        return new SourceFact(UNAVAILABLE, null, null, null, null);
    }
}
