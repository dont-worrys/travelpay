package com.travelpay.exchange.rate.koreaexim;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 한국수출입은행 환율 API 설정입니다. API 키가 없으면 서버 시작 단계에서 실패합니다.
 *
 * @param collectCron   평일 고시 시간대 수집 시도 주기 (서울 시간 기준)
 * @param staleAfterDays 최신 고시일이 이 일수보다 오래되면 견적에 쓰지 않습니다
 */
@Validated
@ConfigurationProperties("exchange.rate.koreaexim")
public record KoreaEximProperties(
        @NotBlank String apiKey,
        @NotNull @DefaultValue("https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON") URI baseUrl,
        @NotBlank @DefaultValue("0 */10 11-13 * * MON-FRI") String collectCron,
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        @NotNull @DefaultValue("5s") Duration readTimeout,
        @Positive @DefaultValue("7") int staleAfterDays
) {
}
