package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;

import java.util.UUID;

/**
 * MANUAL_REVIEW 처리 결과. refundId가 있으면 환불 처리이고, toStatus는 처리 대상(결제 또는 환불)의 바뀐 상태입니다.
 * paymentStatus는 처리 후 결제 상태입니다(환불 처리면 REFUND_PENDING·APPROVED·REFUNDED 중 하나).
 */
public record ManualReviewResolveResult(UUID paymentId, UUID refundId, String toStatus, PaymentStatus paymentStatus,
                                        ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId) {
}
