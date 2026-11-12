package com.travelpay.exchange.presentation.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.travelpay.exchange.application.rate.LatestRateWithChange;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.RateChange;

/**
 * 통화별 환율 응답입니다. 환율 값은 unit 단위 외화의 원화 가격입니다. (예: JPY unit=100이면 100엔당 가격)
 * 환율은 부동소수점 오차 없이 전달하도록 문자열로 내려줍니다.
 *
 * 전일 대비(#272)는 매매기준율로 비교합니다. 전일은 최신 고시일보다 앞선 날 중 가장 최근 날의 마지막 값입니다
 * (공식 환율이면 직전 영업일, mock이면 전날 마지막 값). 전일 환율이 없으면(수집을 시작한 첫날 등) 네 필드 모두 null입니다.
 *
 * @param buyRate          고객이 외화를 살 때 환율 (은행 고시 참고값. 견적은 매매기준율 + 수수료)
 * @param sellRate         고객이 외화를 팔 때 환율 (은행 고시 참고값)
 * @param previousMidRate  전일 매매기준율
 * @param previousRateDate 전일 고시일
 * @param changeAmount     전일 대비 변동 값(최신 − 전일). 음수면 내림
 * @param changeRate       전일 대비 변동률(%). 소수 둘째 자리 반올림
 */
public record ExchangeRateResponse(
        String currency,
        String priceCurrency,
        int unit,
        String midRate,
        String buyRate,
        String sellRate,
        String source,
        LocalDate rateDate,
        Instant collectedAt,
        String previousMidRate,
        LocalDate previousRateDate,
        String changeAmount,
        String changeRate
) {

    public static ExchangeRateResponse from(LatestRateWithChange latest) {
        ExchangeRate rate = latest.rate();
        RateChange change = latest.change();
        return new ExchangeRateResponse(
                rate.getCurrency().name(),
                rate.getPriceCurrency(),
                rate.getUnit(),
                plain(rate.getMidRate()),
                plain(rate.getBuyRate()),
                plain(rate.getSellRate()),
                rate.getSource().name(),
                rate.getRateDate(),
                rate.getCollectedAt(),
                change == null ? null : plain(change.previousMidRate()),
                change == null ? null : change.previousRateDate(),
                change == null ? null : plain(change.amount()),
                change == null ? null : change.percent().toPlainString()
        );
    }

    /** DB의 NUMERIC(18,8) 자릿수(838.30000000)를 정리해 838.3처럼 표기합니다. 0은 "0"입니다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
