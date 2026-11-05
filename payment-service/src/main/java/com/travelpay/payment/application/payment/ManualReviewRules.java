package com.travelpay.payment.application.payment;

import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.RefundStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.util.ArrayList;
import java.util.List;

/**
 * MANUAL_REVIEW 처리 규칙. 멈춘 사유와 원장 조회 결과로 처리 결과를 정합니다.
 * 단건 조회(예상 결과)와 처리 API가 같은 규칙을 써서, 미리 보여 준 결과와 실제 처리가 어긋나지 않게 합니다.
 */
final class ManualReviewRules {

    static final String PAYMENT_TIMEOUT_REASON = "결제 처리 시간이 초과되어 취소되었습니다.";
    static final String PAYMENT_ERROR_REASON = "결제 처리 중 오류로 취소되었습니다.";
    static final String REFUND_ERROR_REASON = "환불 처리 중 오류로 취소되었습니다.";

    private static final String CORRECTION_REQUIRED = "원장 보정 필요(처리 불가)";

    /** resolvable이 false면 원장 거래 내용이 우리 기록과 달라 처리할 수 없음(원장 보정 필요). 그때 toStatus·declineReason은 없음 */
    record PaymentDecision(boolean resolvable, PaymentStatus toStatus, String declineReason, String description) {
    }

    /** resolvable이 false면 원장 거래 내용이 우리 기록과 달라 처리할 수 없음(원장 보정 필요). 그때 toStatus·declineReason은 없음 */
    record RefundDecision(boolean resolvable, RefundStatus toStatus, String declineReason, String description) {
    }

    private ManualReviewRules() {
    }

    /**
     * expectedEntries: 우리 결제·환불대로라면 원장에 있어야 할 거래 항목.
     * 원장이 POSTED면 멈춘 사유와 상관없이 항목으로 판단합니다. 같으면 돈이 의도대로 움직인 것이라 기록을 맞추고(송금과 같은 정책),
     * 다르면(금액·지갑 등) 돈이 우리 기록과 다르게 움직인 것이라 처리하지 않습니다.
     */
    static PaymentDecision decidePayment(SettlementReviewReason reviewReason, PaymentLedgerLookup ledger,
                                         List<PaymentLedgerLookup.Entry> expectedEntries) {
        boolean exhausted = reviewReason == SettlementReviewReason.RETRY_EXHAUSTED;
        return switch (ledger.status()) {
            case POSTED -> !sameEntries(ledger.entries(), expectedEntries)
                    ? new PaymentDecision(false, null, null, describeMismatch(reviewReason))                          // P5 내용 다름
                    : payment(PaymentStatus.APPROVED, null, reviewReason, ledger, "결제 승인");                         // P1·P5 내용 같음
            case REJECTED -> exhausted
                    ? payment(PaymentStatus.DECLINED, ledger.declineReason(), reviewReason, ledger, "결제 거절")         // P2
                    : payment(PaymentStatus.DECLINED, PAYMENT_ERROR_REASON, reviewReason, ledger, "결제 취소");          // P4
            case NOT_FOUND -> exhausted
                    ? payment(PaymentStatus.DECLINED, PAYMENT_TIMEOUT_REASON, reviewReason, ledger, "결제 시간 초과 취소") // P3
                    : payment(PaymentStatus.DECLINED, PAYMENT_ERROR_REASON, reviewReason, ledger, "결제 취소");          // P4
        };
    }

    static RefundDecision decideRefund(SettlementReviewReason reviewReason, PaymentLedgerLookup ledger,
                                       List<PaymentLedgerLookup.Entry> expectedEntries) {
        boolean exhausted = reviewReason == SettlementReviewReason.RETRY_EXHAUSTED;
        return switch (ledger.status()) {
            case POSTED -> !sameEntries(ledger.entries(), expectedEntries)
                    ? new RefundDecision(false, null, null, describeMismatch(reviewReason))                           // R5 내용 다름
                    : refund(RefundStatus.COMPLETED, null, reviewReason, ledger, "환불 완료");                          // R1·R5 내용 같음
            case REJECTED -> exhausted
                    ? refund(RefundStatus.DECLINED, ledger.declineReason(), reviewReason, ledger, "환불 거절·취소")       // R2
                    : refund(RefundStatus.DECLINED, REFUND_ERROR_REASON, reviewReason, ledger, "환불 거절·취소");        // R4
            case NOT_FOUND -> exhausted
                    ? refund(RefundStatus.REQUESTED, null, reviewReason, ledger, "환불 재시도")                          // R3
                    : refund(RefundStatus.DECLINED, REFUND_ERROR_REASON, reviewReason, ledger, "환불 거절·취소");        // R4
        };
    }

    /**
     * 환불 처리 후 결제 상태. 완료면 남은 금액이 0일 때 REFUNDED, 아니면 APPROVED(부분 환불), 거절은 APPROVED, 재시도는 REFUND_PENDING 유지.
     * refundedBefore는 이 환불을 뺀 완료 환불 합계입니다.
     */
    static PaymentStatus paymentStatusAfterRefund(RefundStatus refundStatus, long paymentAmount,
                                                  long refundedBefore, long refundAmount) {
        return switch (refundStatus) {
            case COMPLETED -> paymentAmount - refundedBefore - refundAmount == 0
                    ? PaymentStatus.REFUNDED : PaymentStatus.APPROVED;
            case DECLINED -> PaymentStatus.APPROVED;
            default -> PaymentStatus.REFUND_PENDING;
        };
    }

    private static PaymentDecision payment(PaymentStatus toStatus, String declineReason,
                                           SettlementReviewReason reviewReason, PaymentLedgerLookup ledger,
                                           String outcome) {
        return new PaymentDecision(true, toStatus, declineReason, describe(reviewReason, ledger, outcome));
    }

    private static RefundDecision refund(RefundStatus toStatus, String declineReason,
                                         SettlementReviewReason reviewReason, PaymentLedgerLookup ledger,
                                         String outcome) {
        return new RefundDecision(true, toStatus, declineReason, describe(reviewReason, ledger, outcome));
    }

    // 원장 항목과 기대 항목이 같은 묶음인지(순서 무관, 개수까지 같아야 함)
    private static boolean sameEntries(List<PaymentLedgerLookup.Entry> actual,
                                       List<PaymentLedgerLookup.Entry> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        List<PaymentLedgerLookup.Entry> remaining = new ArrayList<>(actual);
        for (PaymentLedgerLookup.Entry entry : expected) {
            if (!remaining.remove(entry)) {
                return false;
            }
        }
        return true;
    }

    private static String describeMismatch(SettlementReviewReason reviewReason) {
        return cause(reviewReason) + ", 원장 반영 확인(금액·지갑 불일치) → " + CORRECTION_REQUIRED;
    }

    private static String cause(SettlementReviewReason reviewReason) {
        return reviewReason == SettlementReviewReason.RETRY_EXHAUSTED ? "재시도 한도 초과" : "원장 요청 거부";
    }

    // 처리 기록 설명: 멈춘 사유, 원장 조회 결과, 처리 결과를 한 문장으로
    private static String describe(SettlementReviewReason reviewReason, PaymentLedgerLookup ledger, String outcome) {
        String ledgerResult = switch (ledger.status()) {
            case POSTED -> "원장 반영 확인";
            case REJECTED -> "원장 거절 확인";
            case NOT_FOUND -> "원장 기록 없음";
        };
        return cause(reviewReason) + ", " + ledgerResult + " → " + outcome;
    }
}
