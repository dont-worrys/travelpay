package com.travelpay.exchange.application.limit;

import java.util.List;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.quote.AmountRange;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import org.junit.jupiter.api.Test;

import static com.travelpay.exchange.domain.quote.QuoteFixtures.range;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExchangeLimitQueryServiceTest {

    private final ExchangeLimitQueryService service = new ExchangeLimitQueryService(QuoteFixtures.policy());

    /** 정책의 한도 맵은 순서를 보장하지 않으므로, 통화 선언 순서로 돌려주는지 확인합니다. */
    @Test
    void returnsLimitsOfEveryCurrencyInDeclarationOrder() {
        List<AmountRange> limits = service.findAll();

        assertEquals(List.of(
                range(Currency.KRW, 1000, 2_000_000),
                range(Currency.JPY, 100, 200_000),
                range(Currency.CNY, 1000, 1_000_000)
        ), limits);
    }
}
