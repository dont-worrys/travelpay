package com.travelpay.exchange.application.order;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ManualReviewResolutionReason;

/**
 * 관리자 MANUAL_REVIEW 처리 완료 결과입니다.
 */
public record AdminExchangeResolveResult(
        UUID orderId,
        ExchangeOrderStatus status,
        ExchangeFailureReason failureReason,
        ManualReviewResolutionReason reason,
        UUID ledgerTransactionId,
        Instant settledAt
) {}
