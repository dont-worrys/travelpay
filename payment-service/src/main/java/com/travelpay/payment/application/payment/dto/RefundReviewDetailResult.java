package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.SettlementReviewReason;

import java.time.Instant;
import java.util.UUID;

/**
 * MANUAL_REVIEW 환불 단건: 환불·결제 정보와 원장 조회 결과·예상 처리 결과.
 * customerUserId는 결제한 고객, requesterUserId는 환불을 실행한 사람, refundedAmount는 이 환불을 뺀 완료 환불 합계입니다.
 */
public record RefundReviewDetailResult(UUID refundId, UUID paymentId, UUID customerUserId, UUID merchantId,
                                       UUID requesterUserId, Long amount, CurrencyCode currency, Long paymentAmount,
                                       long refundedAmount, SettlementReviewReason reviewReason, int attemptCount,
                                       Instant createdAt, Instant updatedAt, ManualReviewPreview preview) {
    public static RefundReviewDetailResult of(Refund refund, Payment payment, long refundedAmount,
                                              ManualReviewPreview preview) {
        return new RefundReviewDetailResult(refund.getId(), payment.getId(), payment.getUserId(),
                payment.getMerchantId(), refund.getUserId(), refund.getAmount(), refund.getCurrency(),
                payment.getAmount(), refundedAmount, refund.getReviewReason(), refund.getAttemptCount(),
                refund.getCreatedAt(), refund.getUpdatedAt(), preview);
    }
}
