package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.time.Instant;
import java.util.UUID;

/** MANUAL_REVIEW 결제 단건: 결제 정보와 원장 조회 결과·예상 처리 결과 */
public record PaymentReviewDetailResult(UUID paymentId, UUID userId, UUID merchantId, String merchantName,
                                        Long amount, CurrencyCode currency, SettlementReviewReason reviewReason,
                                        int attemptCount, Instant createdAt, Instant updatedAt,
                                        ManualReviewPreview preview) {
    public static PaymentReviewDetailResult of(Payment payment, ManualReviewPreview preview) {
        return new PaymentReviewDetailResult(payment.getId(), payment.getUserId(), payment.getMerchantId(),
                payment.getMerchantName(), payment.getAmount(), payment.getCurrency(), payment.getReviewReason(),
                payment.getAttemptCount(), payment.getCreatedAt(), payment.getUpdatedAt(), preview);
    }
}
