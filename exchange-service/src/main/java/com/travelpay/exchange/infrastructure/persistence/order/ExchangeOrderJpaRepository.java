package com.travelpay.exchange.infrastructure.persistence.order;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExchangeOrderJpaRepository extends JpaRepository<ExchangeOrder, UUID> {

    Optional<ExchangeOrder> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Optional<ExchangeOrder> findByIdAndUserId(UUID id, UUID userId);
}
