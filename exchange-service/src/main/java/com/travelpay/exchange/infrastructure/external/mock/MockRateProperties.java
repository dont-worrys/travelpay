package com.travelpay.exchange.infrastructure.external.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import com.travelpay.exchange.domain.rate.ForeignCurrency;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 가짜 환율 설정입니다(exchange.rate.mode=mock일 때만 읽음). 값이 규칙에 맞지 않으면 서버 시작 단계에서 실패합니다.
 *
 * @param interval         수집 간격. 앞 수집이 끝난 뒤 이 시간만큼 쉬고 다시 수집합니다
 * @param maxStepRate      한 번에 움직이는 최대 비율 (0.0005 = ±0.05%)
 * @param maxDeviationRate 기준값에서 벗어날 수 있는 최대 비율 (0.02 = ±2%)
 * @param staleAfter       마지막 수집이 이 시간보다 오래되면 견적에 쓰지 않습니다
 * @param defaultMidRates  공식 환율이 DB에 없을 때 쓸 통화별 매매기준율 (통화의 고시 단위 기준)
 */
@Validated
@ConfigurationProperties("exchange.rate.mock")
public record MockRateProperties(
        @NotNull @DefaultValue("5s") Duration interval,
        @NotNull @DefaultValue("0.0005") BigDecimal maxStepRate,
        @NotNull @DefaultValue("0.02") BigDecimal maxDeviationRate,
        @NotNull @DefaultValue("30s") Duration staleAfter,
        @NotEmpty Map<ForeignCurrency, BigDecimal> defaultMidRates
) {
}
