package com.travelpay.exchange.application.order;

import java.util.Objects;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderType;

/**
 * 환전 주문 목록 조건입니다. 회원 본인의 주문만 대상입니다.
 *
 * @param status 주문 상태. null이면 모든 상태
 * @param type   환전·재환전. null이면 둘 다
 */
public record ExchangeOrderListCondition(UUID userId, ExchangeOrderStatus status, ExchangeOrderType type) {

    public ExchangeOrderListCondition {
        Objects.requireNonNull(userId, "userId");
    }
}
