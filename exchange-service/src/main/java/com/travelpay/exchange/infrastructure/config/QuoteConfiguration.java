package com.travelpay.exchange.infrastructure.config;

import java.util.EnumMap;
import java.util.Map;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.AmountRange;
import com.travelpay.exchange.domain.quote.FeeRate;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 설정값으로 견적 발급 규칙을 만듭니다. 규칙 검증은 도메인(QuotePolicy, FeeRate, AmountRange)이 합니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuoteProperties.class)
public class QuoteConfiguration {

    @Bean
    public QuotePolicy quotePolicy(QuoteProperties properties) {
        Map<Currency, AmountRange> limits = new EnumMap<>(Currency.class);
        properties.limits().forEach((currency, limit) -> limits.put(currency,
                new AmountRange(Money.of(currency, limit.min()), Money.of(currency, limit.max()))));
        return new QuotePolicy(properties.ttl(), new FeeRate(properties.feeRate()), limits);
    }
}
