package com.travelpay.payment.application.payment.dto;

import java.util.List;
import java.util.UUID;

public record StuckSettlementResult(
        long paymentCount,
        List<UUID> paymentIds,
        long refundCount,
        List<UUID> refundIds
) {
}
