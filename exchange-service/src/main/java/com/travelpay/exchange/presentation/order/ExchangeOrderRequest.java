package com.travelpay.exchange.presentation.order;

import java.util.UUID;

import com.travelpay.exchange.application.order.PlaceOrderCommand;
import jakarta.validation.constraints.NotNull;

/**
 * 환전 신청 요청입니다. 금액·통화는 견적에 있으므로 받지 않습니다.
 *
 * @param quoteId  사용할 견적 ID
 * @param walletId 돈을 빼고 받을 본인 지갑 ID
 */
public record ExchangeOrderRequest(
        @NotNull(message = "견적 ID를 입력해 주세요.") UUID quoteId,
        @NotNull(message = "지갑 ID를 입력해 주세요.") UUID walletId
) {

    public PlaceOrderCommand toCommand(UUID userId, String idempotencyKey) {
        return new PlaceOrderCommand(userId, idempotencyKey, quoteId, walletId);
    }
}
