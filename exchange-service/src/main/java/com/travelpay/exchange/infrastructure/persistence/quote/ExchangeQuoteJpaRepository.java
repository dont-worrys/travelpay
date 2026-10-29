package com.travelpay.exchange.infrastructure.persistence.quote;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.quote.ExchangeQuote;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExchangeQuoteJpaRepository extends JpaRepository<ExchangeQuote, UUID> {

    Optional<ExchangeQuote> findByIdAndUserId(UUID id, UUID userId);
}
