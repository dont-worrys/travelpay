package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.application.payment.dto.RefundRequestSummary.Result;
import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RefundRequestSummaryTest {

    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

    @Test
    void returnsNullWhenNeverRequested() {
        assertNull(RefundRequestSummary.from(List.of(created(), approved())));
    }

    @Test
    void requestedWhileWaitingForMerchant() {
        RefundRequestSummary summary = RefundRequestSummary.from(List.of(
                created(), approved(), requested(10, "사이즈가 맞지 않음")));

        assertEquals(Result.REQUESTED, summary.result());
        assertEquals(T0.plusSeconds(10), summary.requestedAt());
        assertEquals("사이즈가 맞지 않음", summary.requestReason());
        assertNull(summary.processedAt());
        assertNull(summary.rejectReason());
    }

    @Test
    void cancelledByCustomer() {
        RefundRequestSummary summary = RefundRequestSummary.from(List.of(
                created(), approved(), requested(10, "단순 변심"),
                history(PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED, null, PaymentActor.USER, 20)));

        assertEquals(Result.CANCELLED, summary.result());
        assertEquals(T0.plusSeconds(20), summary.processedAt());
        assertNull(summary.rejectReason());
    }

    @Test
    void rejectedByMerchantOrMasterWithReason() {
        RefundRequestSummary byMerchant = RefundRequestSummary.from(List.of(
                created(), approved(), requested(10, "단순 변심"),
                history(PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED, "사용 흔적이 있음", PaymentActor.MERCHANT, 20)));
        RefundRequestSummary byMaster = RefundRequestSummary.from(List.of(
                created(), approved(), requested(10, "단순 변심"),
                history(PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED, "환불 대상 아님", PaymentActor.MASTER, 20)));

        assertEquals(Result.REJECTED, byMerchant.result());
        assertEquals("사용 흔적이 있음", byMerchant.rejectReason());
        assertEquals(T0.plusSeconds(20), byMerchant.processedAt());
        assertEquals(Result.REJECTED, byMaster.result());
        assertEquals("환불 대상 아님", byMaster.rejectReason());
    }

    @Test
    void acceptedWhenMerchantStartsRefund() {
        RefundRequestSummary summary = RefundRequestSummary.from(List.of(
                created(), approved(), requested(10, "단순 변심"),
                history(PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUND_PENDING, "단순 변심", PaymentActor.MERCHANT, 20),
                history(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED, null, PaymentActor.SYSTEM, 21)));

        assertEquals(Result.ACCEPTED, summary.result());
        assertEquals(T0.plusSeconds(20), summary.processedAt());
    }

    // 거절 뒤 다시 신청하면 가장 최근 신청 기준
    @Test
    void usesLatestRequestAfterRejection() {
        RefundRequestSummary summary = RefundRequestSummary.from(List.of(
                created(), approved(), requested(10, "처음 신청"),
                history(PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED, "사용 흔적이 있음", PaymentActor.MERCHANT, 20),
                requested(30, "다시 신청")));

        assertEquals(Result.REQUESTED, summary.result());
        assertEquals("다시 신청", summary.requestReason());
        assertEquals(T0.plusSeconds(30), summary.requestedAt());
        assertNull(summary.rejectReason());
    }

    private static PaymentHistory created() {
        return history(null, PaymentStatus.PENDING, null, PaymentActor.USER, 0);
    }

    private static PaymentHistory approved() {
        return history(PaymentStatus.PENDING, PaymentStatus.APPROVED, null, PaymentActor.SYSTEM, 1);
    }

    private static PaymentHistory requested(long seconds, String reason) {
        return history(PaymentStatus.APPROVED, PaymentStatus.REFUND_REQUESTED, reason, PaymentActor.USER, seconds);
    }

    private static PaymentHistory history(PaymentStatus from, PaymentStatus to, String reason, PaymentActor actor,
                                          long seconds) {
        PaymentHistory history = PaymentHistory.of(PAYMENT_ID, from, to, reason, actor);
        ReflectionTestUtils.setField(history, "createdAt", T0.plusSeconds(seconds));
        return history;
    }
}
