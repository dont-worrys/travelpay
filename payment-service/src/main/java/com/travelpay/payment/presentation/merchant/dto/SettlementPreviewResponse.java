package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementPreviewResult;

import java.time.Instant;

public record SettlementPreviewResponse(
        String currency,
        Summary available,
        Summary nextSettlement,
        Summary waiting,
        Instant nextAvailableAt
) {

    public record Summary(int count, long amount) {
        static Summary from(SettlementPreviewResult.Summary summary) {
            return new Summary(summary.count(), summary.amount());
        }
    }

    public static SettlementPreviewResponse from(SettlementPreviewResult result) {
        return new SettlementPreviewResponse(
                result.currency().name(),
                Summary.from(result.available()),
                Summary.from(result.nextSettlement()),
                Summary.from(result.waiting()),
                result.nextAvailableAt());
    }
}
