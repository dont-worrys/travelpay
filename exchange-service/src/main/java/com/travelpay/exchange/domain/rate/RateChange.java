package com.travelpay.exchange.domain.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 최신 환율의 전일 대비 변동입니다(#272). 매매기준율로 비교합니다.
 * 전일은 최신 고시일보다 앞선 날 중 가장 최근 날의 마지막 값입니다(ExchangeRateRepository.findLatestBefore).
 *
 * @param previousMidRate  전일 매매기준율
 * @param previousRateDate 전일 고시일
 * @param amount           최신 − 전일. 음수면 내림. 두 환율 모두 고시 자릿수라 반올림 없이 그대로 뺍니다
 * @param percent          변동률(%). 소수 둘째 자리 반올림(HALF_UP). 돈이 아니라 보여 주는 값이라 일반 반올림입니다
 */
public record RateChange(BigDecimal previousMidRate, LocalDate previousRateDate, BigDecimal amount, BigDecimal percent) {

    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public RateChange {
        Objects.requireNonNull(previousMidRate, "previousMidRate");
        Objects.requireNonNull(previousRateDate, "previousRateDate");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(percent, "percent");
    }

    /**
     * 같은 통화·출처·단위의 두 환율로 변동을 계산합니다.
     *
     * @throws IllegalArgumentException 통화·출처·단위가 다르거나 전일 고시일이 최신 고시일보다 앞서지 않은 경우
     */
    public static RateChange between(ExchangeRate latest, ExchangeRate previous) {
        if (latest.getCurrency() != previous.getCurrency() || latest.getSource() != previous.getSource()
                || latest.getUnit() != previous.getUnit()) {
            throw new IllegalArgumentException("같은 통화·출처·단위의 환율끼리만 비교합니다: latest="
                    + latest.getCurrency() + "/" + latest.getSource() + "/" + latest.getUnit()
                    + ", previous=" + previous.getCurrency() + "/" + previous.getSource() + "/" + previous.getUnit());
        }
        if (!previous.getRateDate().isBefore(latest.getRateDate())) {
            throw new IllegalArgumentException("전일 고시일은 최신 고시일보다 앞서야 합니다: latest="
                    + latest.getRateDate() + ", previous=" + previous.getRateDate());
        }
        BigDecimal amount = latest.getMidRate().subtract(previous.getMidRate());
        BigDecimal percent = amount.multiply(HUNDRED).divide(previous.getMidRate(), PERCENT_SCALE, RoundingMode.HALF_UP);
        return new RateChange(previous.getMidRate(), previous.getRateDate(), amount, percent);
    }
}
