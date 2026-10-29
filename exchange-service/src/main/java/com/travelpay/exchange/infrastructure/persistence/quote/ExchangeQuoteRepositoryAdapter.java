package com.travelpay.exchange.infrastructure.persistence.quote;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 견적 저장소를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeQuoteRepositoryAdapter implements ExchangeQuoteRepository {

    private final ExchangeQuoteJpaRepository jpaRepository;

    @Override
    public ExchangeQuote save(ExchangeQuote quote) {
        return jpaRepository.save(quote);
    }

    @Override
    public Optional<ExchangeQuote> findByIdAndUserId(UUID quoteId, UUID userId) {
        return jpaRepository.findByIdAndUserId(quoteId, userId);
    }
}
