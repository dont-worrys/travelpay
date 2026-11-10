package com.travelpay.exchange.presentation.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.application.order.AdminExchangeReviewDetailResult;
import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.order.ExchangeOrder;

public record AdminExchangeReviewDetailResponse(
        UUID orderId,
        UUID userId,
        UUID walletId,
        String type,
        String status,
        String fromCurrency,
        String fromAmount,
        String toCurrency,
        String toAmount,
        String feeAmount,
        String totalDebitAmount,
        String appliedRate,
        int rateUnit,
        String reviewReason,
        int ledgerCheckCount,
        Instant requestedAt,
        Instant createdAt,
        Instant updatedAt,
        boolean ledgerLookupFailed,
        LedgerResponse ledger,
        ExpectedResponse expected
) {

    public record EntryResponse(
            String ownerType,
            UUID walletId,
            String accountType,
            String currency,
            String direction,
            long amount,
            String entryType
    ) {
        public static EntryResponse from(LedgerLookupResult.Entry entry) {
            return new EntryResponse(
                    entry.ownerType(),
                    entry.walletId(),
                    entry.accountType(),
                    entry.currency(),
                    entry.direction(),
                    entry.amount(),
                    entry.entryType()
            );
        }
    }

    public record LedgerResponse(
            String status,
            UUID transactionId,
            String rejectReason,
            List<EntryResponse> entries
    ) {
        public static LedgerResponse from(AdminExchangeReviewDetailResult.LedgerSummary summary) {
            if (summary == null) {
                return null;
            }
            List<EntryResponse> entryResponses = summary.entries() != null
                    ? summary.entries().stream().map(EntryResponse::from).toList()
                    : List.of();
            return new LedgerResponse(summary.status(), summary.transactionId(), summary.rejectReason(), entryResponses);
        }
    }

    public record ExpectedResponse(
            boolean resolvable,
            String toStatus,
            String failureReason,
            String reason,
            String description,
            List<EntryResponse> expectedEntries,
            List<EntryResponse> actualEntries
    ) {
        public static ExpectedResponse from(AdminExchangeReviewDetailResult.ExpectedSummary summary) {
            if (summary == null) {
                return null;
            }
            List<EntryResponse> expected = summary.expectedEntries() != null
                    ? summary.expectedEntries().stream().map(EntryResponse::from).toList()
                    : null;
            List<EntryResponse> actual = summary.actualEntries() != null
                    ? summary.actualEntries().stream().map(EntryResponse::from).toList()
                    : null;
            return new ExpectedResponse(
                    summary.resolvable(),
                    summary.toStatus() != null ? summary.toStatus().name() : null,
                    summary.failureReason() != null ? summary.failureReason().name() : null,
                    summary.reason() != null ? summary.reason().name() : null,
                    summary.description(),
                    expected,
                    actual
            );
        }
    }

    public static AdminExchangeReviewDetailResponse from(AdminExchangeReviewDetailResult result) {
        ExchangeOrder order = result.order();
        return new AdminExchangeReviewDetailResponse(
                order.getId(),
                order.getUserId(),
                order.getWalletId(),
                order.type().name(),
                order.getStatus().name(),
                order.getFromCurrency().name(),
                order.fromMoney().amount().toPlainString(),
                order.getToCurrency().name(),
                order.toMoney().amount().toPlainString(),
                order.feeMoney().amount().toPlainString(),
                order.totalDebitMoney().amount().toPlainString(),
                order.getAppliedRate().toPlainString(),
                order.getRateUnit(),
                result.reviewReason() != null ? result.reviewReason().name() : null,
                order.getLedgerCheckCount(),
                order.getRequestedAt(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                result.ledgerLookupFailed(),
                LedgerResponse.from(result.ledger()),
                ExpectedResponse.from(result.expected())
        );
    }
}
