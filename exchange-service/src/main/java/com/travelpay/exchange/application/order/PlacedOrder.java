package com.travelpay.exchange.application.order;

import com.travelpay.exchange.domain.order.ExchangeOrder;

/**
 * 환전 신청 결과입니다.
 *
 * @param created 이번 요청으로 주문을 새로 만들었으면 true, 같은 멱등 키 재요청이라 기존 주문을 돌려주면 false
 */
public record PlacedOrder(ExchangeOrder order, boolean created) {
}
