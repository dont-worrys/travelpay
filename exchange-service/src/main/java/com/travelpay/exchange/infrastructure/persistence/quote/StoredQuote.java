package com.travelpay.exchange.infrastructure.persistence.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteType;

/**
 * Redis에 JSON으로 저장하는 견적 값입니다. 도메인 객체가 JSON 형식을 알지 않도록 저장 모양을 여기서 따로 둡니다.
 * 필드 이름이 곧 JSON 키라, 이름을 바꾸면 이미 저장된 견적(최대 10분치)을 읽지 못합니다.
 */
record StoredQuote(
        UUID id,
        QuoteType quoteType,
        UUID rateId,
        UUID userId,
        Currency fromCurrency,
        Currency toCurrency,
        long fromAmount,
        long toAmount,
        BigDecimal appliedRate,
        int rateUnit,
        LocalDate rateDate,
        BigDecimal feeRate,
        long feeAmount,
        long totalDebitAmount,
        Instant issuedAt,
        Instant expiresAt
) {

    static StoredQuote from(ExchangeQuote quote) {
        return new StoredQuote(quote.getId(), quote.getQuoteType(), quote.getRateId(), quote.getUserId(),
                quote.getFromCurrency(), quote.getToCurrency(), quote.getFromAmount(), quote.getToAmount(),
                quote.getAppliedRate(), quote.getRateUnit(), quote.getRateDate(),
                quote.getFeeRate(), quote.getFeeAmount(), quote.getTotalDebitAmount(),
                quote.getIssuedAt(), quote.getExpiresAt());
    }

    ExchangeQuote toQuote() {
        return ExchangeQuote.restore(id, quoteType, rateId, userId, fromCurrency, toCurrency, fromAmount, toAmount,
                appliedRate, rateUnit, rateDate, feeRate, feeAmount, totalDebitAmount, issuedAt, expiresAt);
    }
}
