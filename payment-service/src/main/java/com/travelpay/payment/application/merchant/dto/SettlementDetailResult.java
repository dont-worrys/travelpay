package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementItem;

import java.util.List;
import java.util.UUID;

/** 정산 상세: 정산 정보와 포함된 결제. 원장이 거절한 정산(FAILED)은 항목을 지워 items가 비어 있습니다. */
public record SettlementDetailResult(SettlementResult settlement, List<Item> items) {

    public record Item(UUID paymentId, Long amount) {
    }

    public static SettlementDetailResult from(Settlement settlement, String merchantName, List<SettlementItem> items) {
        return new SettlementDetailResult(
                SettlementResult.from(settlement, merchantName, items.size()),
                items.stream().map(item -> new Item(item.getPaymentId(), item.getAmount())).toList());
    }
}
