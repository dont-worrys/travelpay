package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ChargeFacts;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.reconciliation.MismatchType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 충전 1건 판정 (외부 호출·DB 없음). 처리 중 상태는 호출자가 걸러서 넘기지 않는다.
 * <p>
 * 규칙마다 필요한 소스를 정하고, 필요한 소스가 모두 확인된(Found·NotFound) 규칙만 판정한다.
 * 조회 실패(Unavailable)로 판정할 수 없는 규칙은 보류하고 SOURCE_UNAVAILABLE 한 건에 실패 소스와 보류 규칙을 남긴다.
 * 확인된 사실만으로 정해지는 어긋남은 SOURCE_UNAVAILABLE과 함께 남는다.
 * <p>
 * 같은 유형이 여러 사유로 나오면 한 건으로 합치고 detail에 사유를 이어 적는다 ((run, charge, type) 중복 금지).
 * STATUS_MISMATCH의 사유는 "상태가 사실과 다름"과 "저장된 ID와 다름"으로 시작해 구분한다.
 */
public final class ChargeReconciliationJudge {

    static final String STATUS_DIFFERS = "상태가 사실과 다름";
    static final String ID_DIFFERS = "저장된 ID와 다름";

    private ChargeReconciliationJudge() {
    }

    public record Finding(MismatchType type, String detail) {
    }

    public static List<Finding> judge(ChargeRequest charge, ChargeFacts facts) {
        Collector out = new Collector(facts);
        switch (charge.getStatus()) {
            case MANUAL_REVIEW -> {
                out.add(MismatchType.REVIEW_REQUIRED, "MANUAL_REVIEW 충전: 판정하지 않고 사실만 기록");
                return out.findings();
            }
            case COMPLETED -> completed(charge, out);
            case FAILED -> failed(out);
            case REFUNDED -> refunded(charge, out);
            default -> throw new IllegalArgumentException("판정 대상이 아닌 상태: " + charge.getStatus());
        }
        storedIds(charge, out);
        return out.findings();
    }

    /** COMPLETED: 출금 SUCCEEDED + 원장 POSTED(지갑 CHARGE_IN 금액·통화 일치) + 환불 성공 없음 */
    private static void completed(ChargeRequest charge, Collector out) {
        out.withDebit("출금 성공 확인", debit -> {
            if (!isSucceeded(debit)) {
                out.add(MismatchType.CREDIT_WITHOUT_BANK_DEBIT,
                        "COMPLETED인데 은행 출금 성공 없음 (출금 " + bankStatus(debit) + ")");
            }
        });
        out.withLedger("원장 적립 확인", ledger -> {
            if (!isPosted(ledger)) {
                out.add(MismatchType.BANK_DEBIT_WITHOUT_CREDIT,
                        "COMPLETED인데 원장 적립(POSTED) 없음 (원장 " + ledgerStatus(ledger) + ")");
            }
        });
        out.withRefund("환불 없음 확인", refund -> {
            if (isSucceeded(refund)) {
                out.add(MismatchType.CREDIT_AND_REFUND_BOTH, "COMPLETED인데 은행 환불 성공 있음");
            }
        });
        out.withDebit("출금 금액", debit -> debitAmount(charge, debit, out));
        out.withLedger("원장 금액·통화", ledger -> ledgerAmount(charge, ledger, out));
    }

    /** FAILED: 출금 SUCCEEDED 없음 + 원장 거래 없음 (원장 거래가 있으면 REJECTED라도 정상 흐름에서 나올 수 없음) */
    private static void failed(Collector out) {
        out.withDebit("출금 성공 없음 확인", debit -> {
            if (isSucceeded(debit)) {
                out.add(MismatchType.BANK_DEBIT_WITHOUT_CREDIT, "FAILED인데 은행 출금 성공 있음");
            }
        });
        out.withLedger("원장 거래 없음 확인", ledger -> {
            if (ledger instanceof SourceLookup.Found<LedgerTransactionRecord> found) {
                out.add(MismatchType.STATUS_MISMATCH, STATUS_DIFFERS + ": FAILED인데 원장 거래 있음 (원장 "
                        + found.record().status() + ")");
            }
        });
    }

    /** REFUNDED: 출금 SUCCEEDED + 원장 없음 또는 REJECTED + 환불 SUCCEEDED */
    private static void refunded(ChargeRequest charge, Collector out) {
        out.withDebit("출금 성공 확인", debit -> {
            if (!isSucceeded(debit)) {
                out.add(MismatchType.STATUS_MISMATCH, STATUS_DIFFERS + ": REFUNDED인데 은행 출금 성공 없음 (출금 "
                        + bankStatus(debit) + ")");
            }
        });
        out.withLedger("원장 적립 없음 확인", ledger -> {
            if (isPosted(ledger)) {
                out.add(MismatchType.CREDIT_AND_REFUND_BOTH, "REFUNDED인데 원장 적립(POSTED) 있음");
            }
        });
        out.withRefund("환불 성공 확인", refund -> {
            if (!isSucceeded(refund)) {
                out.add(MismatchType.BANK_DEBIT_WITHOUT_CREDIT,
                        "REFUNDED인데 은행 환불 성공 없음 (환불 " + bankStatus(refund) + ")");
            }
        });
        out.withDebit("출금 금액", debit -> debitAmount(charge, debit, out));
        out.withRefund("환불 금액", refund -> {
            if (refund instanceof SourceLookup.Found<BankTransactionRecord> found
                    && found.record().amount() != charge.getAmount()) {
                out.add(MismatchType.AMOUNT_MISMATCH, "은행 환불 금액(" + found.record().amount()
                        + ") ≠ 충전 금액(" + charge.getAmount() + ")");
            }
        });
    }

    private static void debitAmount(ChargeRequest charge, SourceLookup<BankTransactionRecord> debit, Collector out) {
        if (debit instanceof SourceLookup.Found<BankTransactionRecord> found
                && found.record().amount() != charge.getAmount()) {
            out.add(MismatchType.AMOUNT_MISMATCH, "은행 출금 금액(" + found.record().amount()
                    + ") ≠ 충전 금액(" + charge.getAmount() + ")");
        }
    }

    /** POSTED 원장에서 USER·CREDIT·CHARGE_IN이고 walletId가 충전 지갑인 항목의 금액·통화 = 충전 금액·통화 */
    private static void ledgerAmount(ChargeRequest charge, SourceLookup<LedgerTransactionRecord> ledger,
                                     Collector out) {
        if (!(ledger instanceof SourceLookup.Found<LedgerTransactionRecord> found) || !found.record().isPosted()) {
            return;
        }
        Optional<LedgerEntryRecord> entry = found.record().chargeInEntry(charge.getWalletId());
        if (entry.isEmpty()) {
            out.add(MismatchType.AMOUNT_MISMATCH, "원장에 지갑 적립 항목(USER·CREDIT·CHARGE_IN, walletId="
                    + charge.getWalletId() + ") 없음");
            return;
        }
        if (entry.get().amount() != charge.getAmount()) {
            out.add(MismatchType.AMOUNT_MISMATCH, "원장 적립 금액(" + entry.get().amount()
                    + ") ≠ 충전 금액(" + charge.getAmount() + ")");
        }
        if (!charge.getCurrency().name().equals(entry.get().currency())) {
            out.add(MismatchType.AMOUNT_MISMATCH, "원장 적립 통화(" + entry.get().currency()
                    + ") ≠ 충전 통화(" + charge.getCurrency() + ")");
        }
    }

    /**
     * 충전에 저장된 ID와 찾은 기록의 ID 비교. 기대값은 성공 기록(출금·환불 SUCCEEDED, 원장 POSTED)의 ID, 없으면 null.
     * 성공 기록이 없는데 ID가 저장돼 있거나, 성공 기록이 있는데 저장된 ID가 없거나 다르면 STATUS_MISMATCH
     */
    private static void storedIds(ChargeRequest charge, Collector out) {
        out.withDebit("bank_tx_id 일치", debit ->
                compareId(out, "bank_tx_id", charge.getBankTxId(), succeededTxId(debit)));
        out.withRefund("refund_bank_tx_id 일치", refund ->
                compareId(out, "refund_bank_tx_id", charge.getRefundBankTxId(), succeededTxId(refund)));
        out.withLedger("ledger_ref 일치", ledger -> {
            UUID expected = ledger instanceof SourceLookup.Found<LedgerTransactionRecord> found
                    && found.record().isPosted() ? found.record().transactionId() : null;
            compareId(out, "ledger_ref", charge.getLedgerRef(), expected);
        });
    }

    private static void compareId(Collector out, String column, UUID stored, UUID found) {
        if (!Objects.equals(stored, found)) {
            out.add(MismatchType.STATUS_MISMATCH,
                    ID_DIFFERS + ": " + column + " (저장 " + stored + ", 기록 " + found + ")");
        }
    }

    private static UUID succeededTxId(SourceLookup<BankTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<BankTransactionRecord> found && found.record().isSucceeded()
                ? found.record().bankTxId() : null;
    }

    private static boolean isSucceeded(SourceLookup<BankTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<BankTransactionRecord> found && found.record().isSucceeded();
    }

    private static boolean isPosted(SourceLookup<LedgerTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<LedgerTransactionRecord> found && found.record().isPosted();
    }

    private static String bankStatus(SourceLookup<BankTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<BankTransactionRecord> found ? found.record().status() : "없음";
    }

    private static String ledgerStatus(SourceLookup<LedgerTransactionRecord> lookup) {
        return lookup instanceof SourceLookup.Found<LedgerTransactionRecord> found ? found.record().status() : "없음";
    }

    private interface Rule<T> {
        void apply(SourceLookup<T> lookup);
    }

    /** 규칙 실행기. 필요한 소스가 Unavailable이면 규칙을 보류하고 기록한다 */
    private static final class Collector {

        private final ChargeFacts facts;
        private final Map<MismatchType, List<String>> reasons = new EnumMap<>(MismatchType.class);
        /** 소스 이름 → (실패 사유, 보류 규칙들) */
        private final Map<String, Held> held = new LinkedHashMap<>();

        private Collector(ChargeFacts facts) {
            this.facts = facts;
        }

        void add(MismatchType type, String reason) {
            reasons.computeIfAbsent(type, k -> new ArrayList<>()).add(reason);
        }

        void withDebit(String rule, Rule<BankTransactionRecord> body) {
            with("은행 출금", facts.debit(), rule, body);
        }

        void withRefund(String rule, Rule<BankTransactionRecord> body) {
            with("은행 환불", facts.refund(), rule, body);
        }

        void withLedger(String rule, Rule<LedgerTransactionRecord> body) {
            with("원장", facts.ledger(), rule, body);
        }

        private <T> void with(String source, SourceLookup<T> lookup, String rule, Rule<T> body) {
            if (lookup instanceof SourceLookup.Unavailable<T> unavailable) {
                held.computeIfAbsent(source, k -> new Held(unavailable.reason(), new ArrayList<>())).rules().add(rule);
                return;
            }
            body.apply(lookup);
        }

        List<Finding> findings() {
            if (!held.isEmpty()) {
                List<String> parts = new ArrayList<>();
                held.forEach((source, h) -> parts.add(source + " 조회 실패(" + h.reason() + "), 보류: "
                        + String.join(", ", h.rules())));
                add(MismatchType.SOURCE_UNAVAILABLE, String.join(" / ", parts));
            }
            List<Finding> findings = new ArrayList<>();
            reasons.forEach((type, list) -> findings.add(new Finding(type, String.join("; ", list))));
            return findings;
        }

        private record Held(String reason, List<String> rules) {
        }
    }
}
