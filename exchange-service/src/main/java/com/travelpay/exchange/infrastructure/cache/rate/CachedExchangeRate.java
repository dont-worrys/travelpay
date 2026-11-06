package com.travelpay.exchange.infrastructure.cache.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;

/**
 * Redis에 JSON으로 넣는 환율 한 건의 모양입니다. 환율 행 전체(ID 포함)를 담아, 캐시에서 읽어도 DB에서 읽은 것과 같은 환율을 돌려줍니다.
 * 엔티티를 바로 JSON으로 만들지 않고 이 모양을 따로 두어, 엔티티 필드가 바뀌어도 캐시 형식이 몰래 바뀌지 않게 합니다.
 */
record CachedExchangeRate(
        UUID id,
        ForeignCurrency currency,
        int unit,
        BigDecimal midRate,
        BigDecimal buyRate,
        BigDecimal sellRate,
        RateSource source,
        LocalDate rateDate,
        Instant collectedAt
) {

    static CachedExchangeRate from(ExchangeRate rate) {
        return new CachedExchangeRate(rate.getId(), rate.getCurrency(), rate.getUnit(),
                rate.getMidRate(), rate.getBuyRate(), rate.getSellRate(),
                rate.getSource(), rate.getRateDate(), rate.getCollectedAt());
    }

    ExchangeRate toExchangeRate() {
        return ExchangeRate.restore(id,
                new CollectedRate(currency, unit, midRate, buyRate, sellRate, rateDate, source), collectedAt);
    }
}
