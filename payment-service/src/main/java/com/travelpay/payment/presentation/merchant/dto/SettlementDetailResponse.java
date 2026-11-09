package com.travelpay.payment.presentation.merchant.dto;

import com.travelpay.payment.application.merchant.dto.SettlementDetailResult;

import java.util.List;
import java.util.UUID;

/** 정산 상세: 정산 정보 + 포함된 결제(결제 ID, 정산 금액 = 결제 금액 - 완료된 환불) */
public record SettlementDetailResponse(SettlementResponse settlement, List<Item> items) {

    public record Item(UUID paymentId, Long amount) {
    }

    public static SettlementDetailResponse from(SettlementDetailResult result) {
        return new SettlementDetailResponse(
                SettlementResponse.from(result.settlement()),
                result.items().stream().map(item -> new Item(item.paymentId(), item.amount())).toList());
    }
}
