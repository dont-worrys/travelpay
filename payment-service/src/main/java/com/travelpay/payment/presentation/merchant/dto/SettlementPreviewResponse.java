package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementPreviewResult;

import java.time.Instant;

public record SettlementPreviewResponse(
        String currency,
        Summary available,
        Summary waiting,
        Instant nextAvailableAt
) {

    public record Summary(int count, long amount) {
    }

    public static SettlementPreviewResponse from(SettlementPreviewResult result) {
        return new SettlementPreviewResponse(
                result.currency().name(),
                new Summary(result.available().count(), result.available().amount()),
                new Summary(result.waiting().count(), result.waiting().amount()),
                result.nextAvailableAt());
    }
}
