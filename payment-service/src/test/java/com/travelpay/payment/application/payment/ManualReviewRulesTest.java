package com.travelpay.payment.application.payment;

import java.util.List;
import java.util.UUID;

import com.travelpay.payment.application.payment.ManualReviewRules.PaymentDecision;
import com.travelpay.payment.application.payment.ManualReviewRules.RefundDecision;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup.Entry;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.RefundStatus;
import org.junit.jupiter.api.Test;

import static com.travelpay.payment.domain.payment.SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED;
import static com.travelpay.payment.domain.payment.SettlementReviewReason.RETRY_EXHAUSTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualReviewRulesTest {

    private static final UUID TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID USER_WALLET = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MERCHANT_WALLET = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final List<Entry> PAYMENT_ENTRIES = List.of(
            new Entry(USER_WALLET, "KRW", "DEBIT", 10_000L, "PAYMENT_OUT"),
            new Entry(MERCHANT_WALLET, "KRW", "CREDIT", 10_000L, "PAYMENT_IN"));
    private static final List<Entry> REFUND_ENTRIES = List.of(
            new Entry(MERCHANT_WALLET, "KRW", "DEBIT", 10_000L, "PAYMENT_REFUND_OUT"),
            new Entry(USER_WALLET, "KRW", "CREDIT", 10_000L, "PAYMENT_REFUND_IN"));

    private static final PaymentLedgerLookup PAYMENT_POSTED = PaymentLedgerLookup.posted(TRANSACTION_ID, PAYMENT_ENTRIES);
    private static final PaymentLedgerLookup REFUND_POSTED = PaymentLedgerLookup.posted(TRANSACTION_ID, REFUND_ENTRIES);
    private static final PaymentLedgerLookup REJECTED = PaymentLedgerLookup.rejected(TRANSACTION_ID, "원장 거절 문구");
    private static final PaymentLedgerLookup NOT_FOUND = PaymentLedgerLookup.notFound();

    private static final String MISMATCH = "재시도 한도 초과, 원장 반영 확인(금액·지갑 불일치) → 원장 보정 필요(처리 불가)";

    @Test
    void paymentDecisions() {
        assertEquals(new PaymentDecision(true, PaymentStatus.APPROVED, null,
                        "재시도 한도 초과, 원장 반영 확인 → 결제 승인"),
                ManualReviewRules.decidePayment(RETRY_EXHAUSTED, PAYMENT_POSTED, PAYMENT_ENTRIES));        // P1
        assertEquals(new PaymentDecision(true, PaymentStatus.DECLINED, "원장 거절 문구",
                        "재시도 한도 초과, 원장 거절 확인 → 결제 거절"),
                ManualReviewRules.decidePayment(RETRY_EXHAUSTED, REJECTED, PAYMENT_ENTRIES));              // P2
        assertEquals(new PaymentDecision(true, PaymentStatus.DECLINED, "결제 처리 시간이 초과되어 취소되었습니다.",
                        "재시도 한도 초과, 원장 기록 없음 → 결제 시간 초과 취소"),
                ManualReviewRules.decidePayment(RETRY_EXHAUSTED, NOT_FOUND, PAYMENT_ENTRIES));             // P3
        assertEquals(new PaymentDecision(true, PaymentStatus.DECLINED, "결제 처리 중 오류로 취소되었습니다.",
                        "원장 요청 거부, 원장 거절 확인 → 결제 취소"),
                ManualReviewRules.decidePayment(LEDGER_REQUEST_NOT_ACCEPTED, REJECTED, PAYMENT_ENTRIES));  // P4
        assertEquals(new PaymentDecision(true, PaymentStatus.DECLINED, "결제 처리 중 오류로 취소되었습니다.",
                        "원장 요청 거부, 원장 기록 없음 → 결제 취소"),
                ManualReviewRules.decidePayment(LEDGER_REQUEST_NOT_ACCEPTED, NOT_FOUND, PAYMENT_ENTRIES)); // P4
        // 원장이 요청을 받지 않았어도 원장 거래가 우리 결제와 같으면 돈은 의도대로 움직인 것 → 승인(송금과 같은 정책)
        assertEquals(new PaymentDecision(true, PaymentStatus.APPROVED, null,
                        "원장 요청 거부, 원장 반영 확인 → 결제 승인"),
                ManualReviewRules.decidePayment(LEDGER_REQUEST_NOT_ACCEPTED, PAYMENT_POSTED, PAYMENT_ENTRIES)); // P5 내용 같음
        assertEquals(new PaymentDecision(false, null, null,
                        "원장 요청 거부, 원장 반영 확인(금액·지갑 불일치) → 원장 보정 필요(처리 불가)"),
                ManualReviewRules.decidePayment(LEDGER_REQUEST_NOT_ACCEPTED,
                        PaymentLedgerLookup.posted(TRANSACTION_ID, List.of(
                                new Entry(USER_WALLET, "KRW", "DEBIT", 500L, "PAYMENT_OUT"),
                                new Entry(MERCHANT_WALLET, "KRW", "CREDIT", 500L, "PAYMENT_IN"))),
                        PAYMENT_ENTRIES));                                                                // P5 내용 다름
    }

    @Test
    void refundDecisions() {
        assertEquals(new RefundDecision(true, RefundStatus.COMPLETED, null,
                        "재시도 한도 초과, 원장 반영 확인 → 환불 완료"),
                ManualReviewRules.decideRefund(RETRY_EXHAUSTED, REFUND_POSTED, REFUND_ENTRIES));           // R1
        assertEquals(new RefundDecision(true, RefundStatus.DECLINED, "원장 거절 문구",
                        "재시도 한도 초과, 원장 거절 확인 → 환불 거절·취소"),
                ManualReviewRules.decideRefund(RETRY_EXHAUSTED, REJECTED, REFUND_ENTRIES));                // R2
        assertEquals(new RefundDecision(true, RefundStatus.REQUESTED, null,
                        "재시도 한도 초과, 원장 기록 없음 → 환불 재시도"),
                ManualReviewRules.decideRefund(RETRY_EXHAUSTED, NOT_FOUND, REFUND_ENTRIES));               // R3
        assertEquals(new RefundDecision(true, RefundStatus.DECLINED, "환불 처리 중 오류로 취소되었습니다.",
                        "원장 요청 거부, 원장 거절 확인 → 환불 거절·취소"),
                ManualReviewRules.decideRefund(LEDGER_REQUEST_NOT_ACCEPTED, REJECTED, REFUND_ENTRIES));    // R4
        assertEquals(new RefundDecision(true, RefundStatus.DECLINED, "환불 처리 중 오류로 취소되었습니다.",
                        "원장 요청 거부, 원장 기록 없음 → 환불 거절·취소"),
                ManualReviewRules.decideRefund(LEDGER_REQUEST_NOT_ACCEPTED, NOT_FOUND, REFUND_ENTRIES));   // R4
        assertEquals(new RefundDecision(true, RefundStatus.COMPLETED, null,
                        "원장 요청 거부, 원장 반영 확인 → 환불 완료"),
                ManualReviewRules.decideRefund(LEDGER_REQUEST_NOT_ACCEPTED, REFUND_POSTED, REFUND_ENTRIES)); // R5 내용 같음
        assertEquals(new RefundDecision(false, null, null,
                        "원장 요청 거부, 원장 반영 확인(금액·지갑 불일치) → 원장 보정 필요(처리 불가)"),
                ManualReviewRules.decideRefund(LEDGER_REQUEST_NOT_ACCEPTED,
                        PaymentLedgerLookup.posted(TRANSACTION_ID, List.of(
                                new Entry(MERCHANT_WALLET, "KRW", "DEBIT", 500L, "PAYMENT_REFUND_OUT"),
                                new Entry(USER_WALLET, "KRW", "CREDIT", 500L, "PAYMENT_REFUND_IN"))),
                        REFUND_ENTRIES));                                                                 // R5 내용 다름
    }

    // 원장 POSTED라도 항목이 우리 기록과 다르면 처리하지 않음
    @Test
    void postedWithDifferentEntriesIsNotResolvable() {
        List<List<Entry>> differentLedgers = List.of(
                // 금액이 다름
                List.of(new Entry(USER_WALLET, "KRW", "DEBIT", 1_000L, "PAYMENT_OUT"),
                        new Entry(MERCHANT_WALLET, "KRW", "CREDIT", 1_000L, "PAYMENT_IN")),
                // 받는 지갑이 다름
                List.of(new Entry(USER_WALLET, "KRW", "DEBIT", 10_000L, "PAYMENT_OUT"),
                        new Entry(UUID.randomUUID(), "KRW", "CREDIT", 10_000L, "PAYMENT_IN")),
                // 통화가 다름
                List.of(new Entry(USER_WALLET, "JPY", "DEBIT", 10_000L, "PAYMENT_OUT"),
                        new Entry(MERCHANT_WALLET, "JPY", "CREDIT", 10_000L, "PAYMENT_IN")),
                // 항목 유형이 다름
                List.of(new Entry(USER_WALLET, "KRW", "DEBIT", 10_000L, "TRANSFER_OUT"),
                        new Entry(MERCHANT_WALLET, "KRW", "CREDIT", 10_000L, "TRANSFER_IN")),
                // 방향이 반대
                List.of(new Entry(USER_WALLET, "KRW", "CREDIT", 10_000L, "PAYMENT_OUT"),
                        new Entry(MERCHANT_WALLET, "KRW", "DEBIT", 10_000L, "PAYMENT_IN")),
                // 항목이 하나 더 있음
                List.of(new Entry(USER_WALLET, "KRW", "DEBIT", 10_000L, "PAYMENT_OUT"),
                        new Entry(MERCHANT_WALLET, "KRW", "CREDIT", 10_000L, "PAYMENT_IN"),
                        new Entry(MERCHANT_WALLET, "KRW", "CREDIT", 10_000L, "PAYMENT_IN")));

        for (List<Entry> ledgerEntries : differentLedgers) {
            PaymentLedgerLookup ledger = PaymentLedgerLookup.posted(TRANSACTION_ID, ledgerEntries);
            assertEquals(new PaymentDecision(false, null, null, MISMATCH),
                    ManualReviewRules.decidePayment(RETRY_EXHAUSTED, ledger, PAYMENT_ENTRIES), ledgerEntries::toString);
        }
        assertFalse(ManualReviewRules.decideRefund(RETRY_EXHAUSTED,
                PaymentLedgerLookup.posted(TRANSACTION_ID, List.of(
                        new Entry(MERCHANT_WALLET, "KRW", "DEBIT", 1_000L, "PAYMENT_REFUND_OUT"),
                        new Entry(USER_WALLET, "KRW", "CREDIT", 1_000L, "PAYMENT_REFUND_IN"))),
                REFUND_ENTRIES).resolvable());
    }

    @Test
    void entryOrderDoesNotMatter() {
        PaymentLedgerLookup reversed = PaymentLedgerLookup.posted(TRANSACTION_ID,
                List.of(PAYMENT_ENTRIES.get(1), PAYMENT_ENTRIES.get(0)));

        assertTrue(ManualReviewRules.decidePayment(RETRY_EXHAUSTED, reversed, PAYMENT_ENTRIES).resolvable());
    }
}
