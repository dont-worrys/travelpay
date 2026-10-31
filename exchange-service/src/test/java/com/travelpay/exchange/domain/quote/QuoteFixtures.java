package com.travelpay.exchange.domain.quote;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 견적 테스트용 값입니다. 설정 파일의 기본값(5분, 1%, 임시 한도)과 같습니다. */
public final class QuoteFixtures {

    public static final LocalDate RATE_DATE = LocalDate.of(2026, 10, 28);
    public static final Instant COLLECTED_AT = Instant.parse("2026-10-28T02:10:00Z");

    private QuoteFixtures() {
    }

    public static QuotePolicy policy() {
        return policyWith(Map.of());
    }

    /** 기본 한도 중 일부 통화만 바꾼 정책입니다. */
    public static QuotePolicy policyWith(Map<Currency, AmountRange> overrides) {
        Map<Currency, AmountRange> limits = new EnumMap<>(Currency.class);
        limits.put(Currency.KRW, range(Currency.KRW, 1000, 2_000_000));
        limits.put(Currency.JPY, range(Currency.JPY, 100, 200_000));
        limits.put(Currency.CNY, range(Currency.CNY, 1000, 1_000_000));
        limits.putAll(overrides);
        return new QuotePolicy(Duration.ofMinutes(5), new FeeRate(new BigDecimal("0.01")), limits);
    }

    /** 최소 단위 금액 범위입니다. */
    public static AmountRange range(Currency currency, long min, long max) {
        return new AmountRange(new Money(currency, min), new Money(currency, max));
    }

    /** DB에 저장된 것처럼 ID가 있는 환율입니다. 매매기준율만 의미 있고 살 때·팔 때 환율은 스프레드를 둡니다. */
    public static ExchangeRate savedRate(ForeignCurrency currency, String midRate) {
        BigDecimal mid = new BigDecimal(midRate);
        ExchangeRate rate = ExchangeRate.collected(new CollectedRate(currency, currency.quoteUnit(), mid,
                mid.multiply(new BigDecimal("1.01")), mid.multiply(new BigDecimal("0.99")),
                RATE_DATE, RateSource.KOREAEXIM), COLLECTED_AT);
        ReflectionTestUtils.setField(rate, "id", UUID.randomUUID());
        return rate;
    }
}
