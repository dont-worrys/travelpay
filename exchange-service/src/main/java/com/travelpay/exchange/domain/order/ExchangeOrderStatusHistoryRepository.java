package com.travelpay.exchange.domain.order;

/** 환전 주문 상태 이력 저장소입니다. 구현은 infrastructure 계층이 담당합니다. */
public interface ExchangeOrderStatusHistoryRepository {

    ExchangeOrderStatusHistory save(ExchangeOrderStatusHistory history);
}
