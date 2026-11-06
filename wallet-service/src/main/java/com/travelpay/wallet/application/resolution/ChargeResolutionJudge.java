package com.travelpay.wallet.application.resolution;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ChargeFacts;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.resolution.ResolutionAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 충전 보정 판정 (순수 로직). 실행 순간 다시 조회한 은행 출금·환불·원장 사실로만 판단한다.
 *
 * <pre>
 * 조치               허용 조건
 * CONFIRM_COMPLETED  출금 SUCCEEDED + 원장 POSTED + 환불 SUCCEEDED 없음
 * CONFIRM_FAILED     (출금 FAILED 또는 확정된 NotFound) + 원장 NotFound + 환불 SUCCEEDED 없음
 * CONFIRM_REFUNDED   출금 SUCCEEDED + 원장 NotFound 또는 REJECTED + 환불 SUCCEEDED
 * RESUME_CREDIT      출금 SUCCEEDED + 원장 확정된 NotFound + 환불 SUCCEEDED 없음
 * RESUME_REFUND      출금 SUCCEEDED + 원장 REJECTED + 환불 확정된 NotFound
 * </pre>
 *
 * 조치와 무관하게 판정할 수 없는 경우(blocker)는 모든 조치를 막는다: 조회 불가, 금액·통화 불일치, 원장 적립 항목 없음,
 * 원장 POSTED와 환불 SUCCEEDED 동시, 환불 FAILED, 알 수 없는 상태·거래 종류, 충전 기록의 거래 ID와 은행·원장 사실이 다름.
 * 맞는 조치가 없으면 가까운 조치에 끼워 맞추지 않고 모두 불가로 둔다.
 */
final class ChargeResolutionJudge {

    private static final String DEBIT = "DEBIT";
    private static final String REFUND = "REFUND";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String POSTED = "POSTED";
    private static final String REJECTED = "REJECTED";

    private ChargeResolutionJudge() {
    }

    /** 소스 하나의 판정용 상태 */
    enum State {
        SUCCEEDED, FAILED, POSTED, REJECTED,
        /** 없음. 확정 시간 전이면 "없음"으로 인정하지 않는다 */
        NOT_FOUND,
        /** 은행·원장이 알려 준 값이 예상 밖 (blocker로 따로 남김) */
        UNKNOWN,
        UNAVAILABLE
    }

    /**
     * @param blockers 모든 조치를 막는 이유
     * @param unmet    조치별로 충족하지 못한 조건 (blocker 제외)
     */
    record Assessment(List<String> blockers, Map<ResolutionAction, List<String>> unmet,
                      UUID verifiedBankTxId, UUID verifiedLedgerRef, UUID verifiedRefundBankTxId) {

        boolean allows(ResolutionAction action) {
            return blockers.isEmpty() && unmet.get(action).isEmpty();
        }

        List<ResolutionAction> availableActions() {
            List<ResolutionAction> available = new ArrayList<>();
            for (ResolutionAction action : ResolutionAction.values()) {
                if (allows(action)) {
                    available.add(action);
                }
            }
            return available;
        }

        /** blocker + 그 조치의 미충족 조건 */
        List<String> reasonsAgainst(ResolutionAction action) {
            List<String> reasons = new ArrayList<>(blockers);
            reasons.addAll(unmet.get(action));
            return reasons;
        }
    }

    static Assessment assess(ChargeRequest charge, ChargeFacts facts, boolean notFoundConfirmed) {
        Objects.requireNonNull(charge, "charge");
        Objects.requireNonNull(facts, "facts");
        List<String> blockers = new ArrayList<>();

        State withdrawal = bankState("은행 출금", facts.debit(), DEBIT, charge.getAmount(), blockers);
        State refund = bankState("은행 환불", facts.refund(), REFUND, charge.getAmount(), blockers);
        State ledger = ledgerState(charge, facts.ledger(), blockers);

        if (refund == State.FAILED) {
            blockers.add("은행 환불이 거절됨(FAILED)");
        }
        if (ledger == State.POSTED && refund == State.SUCCEEDED) {
            blockers.add("원장 적립(POSTED)과 은행 환불 성공(SUCCEEDED)이 동시에 있음");
        }

        UUID bankTxId = withdrawal == State.SUCCEEDED ? found(facts.debit()).bankTxId() : null;
        UUID refundBankTxId = refund == State.SUCCEEDED ? found(facts.refund()).bankTxId() : null;
        UUID ledgerRef = ledger == State.POSTED ? found(facts.ledger()).transactionId() : null;
        if (withdrawal == State.SUCCEEDED && bankTxId == null) {
            blockers.add("은행 출금 성공 거래 ID 없음");
        }
        if (refund == State.SUCCEEDED && refundBankTxId == null) {
            blockers.add("은행 환불 성공 거래 ID 없음");
        }
        // 충전 기록에 이미 있는 거래 ID는 지금 사실과 같아야 한다 (채울 때 coalesce로 덮어쓰지 않으므로)
        recordedIdMatches("출금 거래 ID(bank_tx_id)", charge.getBankTxId(), withdrawal, bankTxId, blockers);
        recordedIdMatches("원장 거래 ID(ledger_ref)", charge.getLedgerRef(), ledger, ledgerRef, blockers);
        recordedIdMatches("환불 거래 ID(refund_bank_tx_id)", charge.getRefundBankTxId(), refund, refundBankTxId,
                blockers);

        Conditions c = new Conditions(withdrawal, refund, ledger, notFoundConfirmed);
        Map<ResolutionAction, List<String>> unmet = new EnumMap<>(ResolutionAction.class);
        unmet.put(ResolutionAction.CONFIRM_COMPLETED, c.collect(
                c.withdrawalIs(State.SUCCEEDED), c.ledgerIs(State.POSTED), c.noRefundSucceeded()));
        unmet.put(ResolutionAction.CONFIRM_FAILED, c.collect(
                c.withdrawalFailedOrConfirmedAbsent(), c.ledgerIs(State.NOT_FOUND), c.noRefundSucceeded()));
        unmet.put(ResolutionAction.CONFIRM_REFUNDED, c.collect(
                c.withdrawalIs(State.SUCCEEDED), c.ledgerAbsentOrRejected(), c.refundIs(State.SUCCEEDED)));
        unmet.put(ResolutionAction.RESUME_CREDIT, c.collect(
                c.withdrawalIs(State.SUCCEEDED), c.ledgerConfirmedAbsent(), c.noRefundSucceeded()));
        unmet.put(ResolutionAction.RESUME_REFUND, c.collect(
                c.withdrawalIs(State.SUCCEEDED), c.ledgerIs(State.REJECTED), c.refundConfirmedAbsent()));
        return new Assessment(Collections.unmodifiableList(blockers), unmet, bankTxId, ledgerRef, refundBankTxId);
    }

    private static State bankState(String label, SourceLookup<BankTransactionRecord> lookup, String expectedType,
                                   long chargeAmount, List<String> blockers) {
        return switch (lookup) {
            case SourceLookup.Unavailable<BankTransactionRecord> ignored -> {
                blockers.add(label + " 조회 불가");
                yield State.UNAVAILABLE;
            }
            case SourceLookup.NotFound<BankTransactionRecord> ignored -> State.NOT_FOUND;
            case SourceLookup.Found<BankTransactionRecord> found -> {
                BankTransactionRecord record = found.record();
                if (!expectedType.equals(record.type())) {
                    blockers.add(label + " 거래 종류가 " + expectedType + "가 아님(" + record.type() + ")");
                }
                if (record.amount() != chargeAmount) {
                    blockers.add(label + " 금액 불일치(은행 " + record.amount() + ", 충전 " + chargeAmount + ")");
                }
                yield switch (String.valueOf(record.status())) {
                    case SUCCEEDED -> State.SUCCEEDED;
                    case FAILED -> State.FAILED;
                    default -> {
                        blockers.add(label + " 상태를 알 수 없음(" + record.status() + ")");
                        yield State.UNKNOWN;
                    }
                };
            }
        };
    }

    private static State ledgerState(ChargeRequest charge, SourceLookup<LedgerTransactionRecord> lookup,
                                     List<String> blockers) {
        return switch (lookup) {
            case SourceLookup.Unavailable<LedgerTransactionRecord> ignored -> {
                blockers.add("원장 조회 불가");
                yield State.UNAVAILABLE;
            }
            case SourceLookup.NotFound<LedgerTransactionRecord> ignored -> State.NOT_FOUND;
            case SourceLookup.Found<LedgerTransactionRecord> found -> {
                LedgerTransactionRecord record = found.record();
                if (!charge.getId().equals(record.referenceId())) {
                    blockers.add("원장 거래의 referenceId가 충전 ID와 다름");
                }
                yield switch (String.valueOf(record.status())) {
                    case POSTED -> {
                        checkChargeInEntry(charge, record, blockers);
                        yield State.POSTED;
                    }
                    case REJECTED -> State.REJECTED;
                    default -> {
                        blockers.add("원장 상태를 알 수 없음(" + record.status() + ")");
                        yield State.UNKNOWN;
                    }
                };
            }
        };
    }

    /** POSTED면 USER·CREDIT·CHARGE_IN·충전 walletId 항목의 금액·통화가 충전과 같아야 한다 */
    private static void checkChargeInEntry(ChargeRequest charge, LedgerTransactionRecord record,
                                           List<String> blockers) {
        Optional<LedgerTransactionRecord.LedgerEntryRecord> entry = record.chargeInEntry(charge.getWalletId());
        if (entry.isEmpty()) {
            blockers.add("원장 적립 항목 없음(USER·CREDIT·CHARGE_IN·충전 지갑)");
            return;
        }
        LedgerTransactionRecord.LedgerEntryRecord e = entry.get();
        if (e.amount() != charge.getAmount() || !charge.getCurrency().name().equals(e.currency())) {
            blockers.add("원장 적립 금액·통화 불일치(원장 " + e.amount() + " " + e.currency() + ", 충전 "
                    + charge.getAmount() + " " + charge.getCurrency() + ")");
        }
    }

    private static void recordedIdMatches(String label, UUID recorded, State state, UUID verified,
                                          List<String> blockers) {
        if (recorded == null || state == State.UNAVAILABLE) {
            return;
        }
        if (!recorded.equals(verified)) {
            blockers.add("충전 기록의 " + label + "와 지금 사실이 다름");
        }
    }

    private static <T> T found(SourceLookup<T> lookup) {
        return ((SourceLookup.Found<T>) lookup).record();
    }

    /** 조건 하나 = 충족하면 null, 아니면 사유 */
    private record Conditions(State withdrawal, State refund, State ledger, boolean notFoundConfirmed) {

        List<String> collect(String... results) {
            List<String> reasons = new ArrayList<>();
            for (String result : results) {
                if (result != null) {
                    reasons.add(result);
                }
            }
            return reasons;
        }

        String withdrawalIs(State expected) {
            return withdrawal == expected ? null : "은행 출금이 " + expected + " 아님(지금 " + describe(withdrawal) + ")";
        }

        String ledgerIs(State expected) {
            return ledger == expected ? null : "원장이 " + expected + " 아님(지금 " + describe(ledger) + ")";
        }

        String refundIs(State expected) {
            return refund == expected ? null : "은행 환불이 " + expected + " 아님(지금 " + describe(refund) + ")";
        }

        String noRefundSucceeded() {
            return refund != State.SUCCEEDED ? null : "은행 환불 SUCCEEDED가 있음";
        }

        String withdrawalFailedOrConfirmedAbsent() {
            if (withdrawal == State.FAILED || confirmedAbsent(withdrawal)) {
                return null;
            }
            return "은행 출금이 FAILED 또는 확정된 NotFound 아님(지금 " + describe(withdrawal) + ")";
        }

        String ledgerAbsentOrRejected() {
            if (ledger == State.NOT_FOUND || ledger == State.REJECTED) {
                return null;
            }
            return "원장이 NotFound 또는 REJECTED 아님(지금 " + describe(ledger) + ")";
        }

        String ledgerConfirmedAbsent() {
            return confirmedAbsent(ledger) ? null : "원장이 확정된 NotFound 아님(지금 " + describe(ledger) + ")";
        }

        String refundConfirmedAbsent() {
            return confirmedAbsent(refund) ? null : "은행 환불이 확정된 NotFound 아님(지금 " + describe(refund) + ")";
        }

        private boolean confirmedAbsent(State state) {
            return state == State.NOT_FOUND && notFoundConfirmed;
        }

        private String describe(State state) {
            if (state == State.NOT_FOUND && !notFoundConfirmed) {
                return "NotFound, MANUAL_REVIEW 전환 후 확정 시간 전";
            }
            return state == State.NOT_FOUND ? "NotFound" : state.name();
        }
    }
}
