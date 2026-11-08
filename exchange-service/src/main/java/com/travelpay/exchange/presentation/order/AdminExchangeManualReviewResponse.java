package com.travelpay.exchange.presentation.order;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.application.order.ExchangeManualReviewView;
import com.travelpay.exchange.domain.order.ExchangeOrder;

public record AdminExchangeManualReviewResponse(
        UUID orderId,
        UUID userId,
        UUID walletId,
        String type,
        String status,
        String fromCurrency,
        String fromAmount,
        String toCurrency,
        String toAmount,
        String totalDebitAmount,
        String reviewReason,
        int ledgerCheckCount,
        Instant requestedAt,
        Instant createdAt
) {
    public static AdminExchangeManualReviewResponse from(ExchangeManualReviewView view) {
        ExchangeOrder order = view.order();
        return new AdminExchangeManualReviewResponse(
                order.getId(),
                order.getUserId(),
                order.getWalletId(),
                order.type().name(),
                order.getStatus().name(),
                order.getFromCurrency().name(),
                order.fromMoney().amount().toPlainString(),
                order.getToCurrency().name(),
                order.toMoney().amount().toPlainString(),
                order.totalDebitMoney().amount().toPlainString(),
                view.reviewReason() != null ? view.reviewReason().name() : null,
                order.getLedgerCheckCount(),
                order.getRequestedAt(),
                order.getCreatedAt()
        );
    }
}
