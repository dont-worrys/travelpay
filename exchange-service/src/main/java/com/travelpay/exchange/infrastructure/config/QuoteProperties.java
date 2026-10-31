package com.travelpay.exchange.infrastructure.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import com.travelpay.exchange.domain.money.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 견적 발급 설정입니다. 값이 규칙에 맞지 않으면 서버 시작 단계에서 실패합니다.
 *
 * @param ttl     견적 유효 시간
 * @param feeRate 수수료율 (0.01 = 1%)
 * @param limits  보내는 통화별 1회 금액 범위 (통화 단위, 예: CNY 10.00). 모든 통화에 있어야 합니다.
 */
@Validated
@ConfigurationProperties("exchange.quote")
public record QuoteProperties(
        @NotNull @DefaultValue("5m") Duration ttl,
        @NotNull @DefaultValue("0.01") BigDecimal feeRate,
        @NotEmpty Map<Currency, @Valid Limit> limits
) {

    public record Limit(@NotNull BigDecimal min, @NotNull BigDecimal max) {
    }
}
