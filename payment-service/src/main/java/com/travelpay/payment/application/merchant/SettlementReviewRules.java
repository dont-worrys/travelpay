package com.travelpay.payment.application.merchant;

import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.util.List;

/**
 * MANUAL_REVIEW 정산 처리 규칙. 멈춘 사유와 원장 조회 결과로 처리 결과를 정합니다.
 * 단건 조회(예상 결과)와 처리 API가 같은 규칙을 써서, 미리 보여 준 결과와 실제 처리가 어긋나지 않게 합니다.
 * 결제와 달리 원장 기록이 없으면 사유와 상관없이 FAILED입니다. 가맹점 주인이 다시 정산을 요청하면 되기 때문입니다.
 */
final class SettlementReviewRules {

    static final String TIMEOUT_REASON = "정산 처리 시간이 초과되어 취소되었습니다.";
    static final String ERROR_REASON = "정산 처리 중 오류로 취소되었습니다.";

    private static final String CORRECTION_REQUIRED = "원장 보정 필요(처리 불가)";

    /** resolvable이 false면 원장 거래 내용이 우리 기록과 달라 처리할 수 없음(원장 보정 필요). 그때 toStatus·failureReason은 없음 */
    record Decision(boolean resolvable, SettlementStatus toStatus, String failureReason, String description) {
    }

    private SettlementReviewRules() {
    }

    /** expectedEntries: 우리 정산대로라면 원장에 있어야 할 거래 항목(가맹점 지갑 DEBIT, 주인 지갑 CREDIT) */
    static Decision decide(SettlementReviewReason reviewReason, PaymentLedgerLookup ledger,
                           List<PaymentLedgerLookup.Entry> expectedEntries) {
        boolean exhausted = reviewReason == SettlementReviewReason.RETRY_EXHAUSTED;
        return switch (ledger.status()) {
            case POSTED -> !ledger.hasSameEntries(expectedEntries)
                    ? new Decision(false, null, null, cause(reviewReason) + ", 원장 반영 확인(금액·지갑 불일치) → "
                            + CORRECTION_REQUIRED)
                    : decision(SettlementStatus.PAID, null, reviewReason, ledger, "정산 지급");
            case REJECTED -> decision(SettlementStatus.FAILED, exhausted ? ledger.declineReason() : ERROR_REASON,
                    reviewReason, ledger, "정산 취소");
            case NOT_FOUND -> decision(SettlementStatus.FAILED, exhausted ? TIMEOUT_REASON : ERROR_REASON,
                    reviewReason, ledger, exhausted ? "정산 시간 초과 취소(원장 취소 기록)" : "정산 취소");
        };
    }

    /** 재시도를 다 쓴 정산이 원장에 없으면 FAILED로 확정하기 전에 원장 취소가 필요 */
    static boolean requiresLedgerCancel(SettlementReviewReason reviewReason, PaymentLedgerLookup ledger) {
        return reviewReason == SettlementReviewReason.RETRY_EXHAUSTED
                && ledger.status() == ReviewLedgerStatus.NOT_FOUND;
    }

    private static Decision decision(SettlementStatus toStatus, String failureReason,
                                     SettlementReviewReason reviewReason, PaymentLedgerLookup ledger, String outcome) {
        String ledgerResult = switch (ledger.status()) {
            case POSTED -> "원장 반영 확인";
            case REJECTED -> "원장 거절 확인";
            case NOT_FOUND -> "원장 기록 없음";
        };
        return new Decision(true, toStatus, failureReason, cause(reviewReason) + ", " + ledgerResult + " → " + outcome);
    }

    private static String cause(SettlementReviewReason reviewReason) {
        return reviewReason == SettlementReviewReason.RETRY_EXHAUSTED ? "재시도 한도 초과" : "원장 요청 거부";
    }
}
