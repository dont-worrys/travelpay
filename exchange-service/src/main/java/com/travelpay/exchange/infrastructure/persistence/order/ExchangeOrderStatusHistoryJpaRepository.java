package com.travelpay.exchange.infrastructure.persistence.order;

import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExchangeOrderStatusHistoryJpaRepository extends JpaRepository<ExchangeOrderStatusHistory, UUID> {
}
