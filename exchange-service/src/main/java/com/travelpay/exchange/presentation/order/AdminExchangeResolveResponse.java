package com.travelpay.exchange.presentation.order;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.application.order.AdminExchangeResolveResult;

public record AdminExchangeResolveResponse(
        UUID orderId,
        String status,
        String failureReason,
        String reason,
        UUID ledgerTransactionId,
        Instant settledAt
) {
    public static AdminExchangeResolveResponse from(AdminExchangeResolveResult result) {
        return new AdminExchangeResolveResponse(
                result.orderId(),
                result.status().name(),
                result.failureReason() != null ? result.failureReason().name() : null,
                result.reason().name(),
                result.ledgerTransactionId(),
                result.settledAt()
        );
    }
}
