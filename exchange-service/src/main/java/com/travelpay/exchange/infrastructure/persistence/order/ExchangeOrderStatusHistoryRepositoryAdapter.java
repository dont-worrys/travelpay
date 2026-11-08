package com.travelpay.exchange.infrastructure.persistence.order;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.ManualReviewReason;
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

    @Override
    public Optional<ManualReviewReason> findLatestManualReviewReason(UUID orderId) {
        return jpaRepository.findManualReviewReasons(orderId).stream()
                .findFirst()
                .flatMap(reason -> {
                    if (reason == null) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of(ManualReviewReason.valueOf(reason));
                    } catch (IllegalArgumentException e) {
                        return Optional.empty();
                    }
                });
    }
}
