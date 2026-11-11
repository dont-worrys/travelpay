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
 * @param redisTimeout 견적 저장소 Redis 명령 하나를 기다리는 최대 시간. 환율 캐시(spring.data.redis.timeout, 200ms)와 따로 둡니다.
 *                     캐시는 넘으면 DB로 읽으면 되지만 견적은 대신 읽을 곳이 없어 바로 503이므로, 순간 지연은 기다려 줍니다(#261)
 */
@Validated
@ConfigurationProperties("exchange.quote")
public record QuoteProperties(
        @NotNull @DefaultValue("5m") Duration ttl,
        @NotNull @DefaultValue("0.01") BigDecimal feeRate,
        @NotEmpty Map<Currency, @Valid Limit> limits,
        @NotNull @DefaultValue("1s") Duration redisTimeout
) {

    public record Limit(@NotNull BigDecimal min, @NotNull BigDecimal max) {
    }
}
