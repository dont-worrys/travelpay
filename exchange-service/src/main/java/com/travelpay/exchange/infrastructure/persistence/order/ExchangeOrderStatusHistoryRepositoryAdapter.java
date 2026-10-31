package com.travelpay.exchange.infrastructure.persistence.order;

import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 환전 주문 이력 저장소를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeOrderStatusHistoryRepositoryAdapter implements ExchangeOrderStatusHistoryRepository {

    private final ExchangeOrderStatusHistoryJpaRepository jpaRepository;

    @Override
    public ExchangeOrderStatusHistory save(ExchangeOrderStatusHistory history) {
        return jpaRepository.save(history);
    }
}
