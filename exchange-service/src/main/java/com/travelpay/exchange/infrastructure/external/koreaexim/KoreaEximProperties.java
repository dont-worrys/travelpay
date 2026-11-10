package com.travelpay.exchange.infrastructure.external.koreaexim;

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
 * @param collectCron   평일 고시 시간대 수집 시도 주기 (서울 시간 기준). 기본은 DEFAULT_COLLECT_CRON
 * @param staleAfterDays 최신 고시일이 이 일수보다 오래되면 견적에 쓰지 않습니다
 */
@Validated
@ConfigurationProperties("exchange.rate.koreaexim")
public record KoreaEximProperties(
        @NotBlank String apiKey,
        @NotNull @DefaultValue("https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON") URI baseUrl,
        @NotBlank @DefaultValue(KoreaEximProperties.DEFAULT_COLLECT_CRON) String collectCron,
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        @NotNull @DefaultValue("5s") Duration readTimeout,
        @Positive @DefaultValue("7") int staleAfterDays
) {

    /**
     * 평일 11:00~13:30, 30분마다 수집을 시도합니다(서울 시간). 그날 고시값을 저장하면 그날은 더 부르지 않아 보통 하루 1~2번 호출하고,
     * 고시가 늦어도 하루 최대 6번입니다. 외부 호출을 줄이려고 10분에서 30분으로 늘렸고(#256), 그 대신 고시 후 반영이 최대 30분 늦을 수 있습니다.
     */
    static final String DEFAULT_COLLECT_CRON = "0 0/30 11-13 * * MON-FRI";
}
