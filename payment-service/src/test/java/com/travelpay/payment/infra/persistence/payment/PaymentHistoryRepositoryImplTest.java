package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentHistoryRepositoryImplTest {

    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID OTHER_PAYMENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private PaymentHistoryJpaRepository jpaRepository;
    private PaymentHistoryRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        jpaRepository = mock(PaymentHistoryJpaRepository.class);
        repository = new PaymentHistoryRepositoryImpl(jpaRepository);
    }

    // 취소·거절 뒤 다시 신청한 결제는 가장 최근 신청 사유를 씁니다. 조회 결과는 최신순입니다.
    @Test
    void usesNewestRefundRequestReasonPerPayment() {
        List<UUID> ids = List.of(PAYMENT_ID, OTHER_PAYMENT_ID);
        when(jpaRepository.findChangedToStatusNewestFirst(ids, PaymentStatus.REFUND_REQUESTED)).thenReturn(List.of(
                requested(PAYMENT_ID, "다시 신청"),
                requested(OTHER_PAYMENT_ID, null),
                requested(PAYMENT_ID, "처음 신청")));

        Map<UUID, String> reasons = repository.findRefundRequestReasons(ids);

        assertEquals(2, reasons.size());
        assertEquals("다시 신청", reasons.get(PAYMENT_ID));
        assertTrue(reasons.containsKey(OTHER_PAYMENT_ID));
        assertNull(reasons.get(OTHER_PAYMENT_ID));
    }

    @Test
    void returnsEmptyWithoutQueryForNoPayments() {
        Map<UUID, String> reasons = repository.findRefundRequestReasons(List.of());

        assertTrue(reasons.isEmpty());
        verifyNoInteractions(jpaRepository);
    }

    private static PaymentHistory requested(UUID paymentId, String reason) {
        return PaymentHistory.of(paymentId, PaymentStatus.APPROVED, PaymentStatus.REFUND_REQUESTED, reason,
                PaymentActor.USER);
    }
}
