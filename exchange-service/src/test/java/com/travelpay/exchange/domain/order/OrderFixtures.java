package com.travelpay.exchange.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuoteType;
import com.travelpay.exchange.domain.rate.ExchangeRate;

/** 주문 테스트용 값입니다. KRW 100,000 → JPY 견적(수수료 1%) 기준입니다. */
public final class OrderFixtures {

    private OrderFixtures() {
    }

    /** 원화 10만 원을 엔화로 바꾸는 견적입니다. 저장 여부는 호출하는 쪽이 정합니다. */
    public static ExchangeQuote wonToYenQuote(UUID userId, ExchangeRate jpyRate, Instant issuedAt) {
        return ExchangeQuote.issue(userId, QuoteType.USER, new QuoteCurrencies(Currency.KRW, Currency.JPY),
                Money.of(Currency.KRW, new BigDecimal("100000")), jpyRate, QuoteFixtures.policy(), issuedAt);
    }

    /** 견적 발급 1분 뒤에 신청한 주문입니다. 견적은 ID와 발급 시각이 있어야 합니다. */
    public static ExchangeOrder placed(ExchangeQuote savedQuote, UUID walletId, String idempotencyKey) {
        return ExchangeOrder.place(savedQuote, walletId, new IdempotencyKey(idempotencyKey),
                savedQuote.getCreatedAt().plusSeconds(60));
    }
}
