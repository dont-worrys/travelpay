package com.travelpay.exchange.infrastructure.external.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;

/**
 * 공식 환율을 중심으로 조금씩 오르내리는 가짜 환율을 만듭니다. 외부를 호출하지 않고 DB에 저장된 값만 읽습니다.
 * 통화마다 두 값을 출처(source)로 구분해 읽습니다.
 * - 기준값(중심): 마지막 공식 환율(KOREAEXIM). 없으면 설정 기본값.
 *   mock 모드에서는 공식 환율을 수집하지 않으므로 기준값은 고정됩니다. 바꾸려면 koreaexim 모드로 한 번 수집합니다.
 * - 직전 값: 마지막 가짜 환율(MOCK). 없으면 기준값. 서버를 다시 켜도 이어서 움직입니다.
 * 고객이 외화를 살 때(buy)·팔 때(sell) 환율은 기준 환율의 매매기준율 대비 비율을 그대로 적용합니다.
 */
public class MockRateProvider implements ExchangeRateProvider {

    /** 공식 환율이 없어 기본값을 쓸 때 살 때·팔 때 환율을 매매기준율의 ±1%로 둔다 */
    static final BigDecimal DEFAULT_SPREAD_RATE = new BigDecimal("0.01");

    private final ExchangeRateRepository repository;
    private final MockRateFluctuation fluctuation;
    private final Map<ForeignCurrency, BigDecimal> defaultMidRates;

    /**
     * @param defaultMidRates 공식 환율이 없을 때 쓸 통화별 매매기준율(통화의 고시 단위 기준). 모든 지원 통화가 있어야 합니다
     */
    public MockRateProvider(ExchangeRateRepository repository, MockRateFluctuation fluctuation,
                            Map<ForeignCurrency, BigDecimal> defaultMidRates) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.fluctuation = Objects.requireNonNull(fluctuation, "fluctuation");
        Objects.requireNonNull(defaultMidRates, "defaultMidRates");
        for (ForeignCurrency currency : ForeignCurrency.values()) {
            BigDecimal rate = defaultMidRates.get(currency);
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("default mid rate for " + currency + " must be positive");
            }
        }
        this.defaultMidRates = new EnumMap<>(defaultMidRates);
    }

    @Override
    public RateSource source() {
        return RateSource.MOCK;
    }

    /** 지원 통화마다 다음 가짜 환율을 하나씩 만듭니다. 고시일은 요청한 날짜입니다. 휴일 없이 항상 값이 있습니다. */
    @Override
    public List<CollectedRate> fetch(LocalDate date) {
        Objects.requireNonNull(date, "date");
        List<CollectedRate> rates = new ArrayList<>();
        for (ForeignCurrency currency : ForeignCurrency.values()) {
            rates.add(next(currency, date));
        }
        return rates;
    }

    private CollectedRate next(ForeignCurrency currency, LocalDate date) {
        Base base = base(currency);
        BigDecimal previous = repository.findLatest(RateSource.MOCK, currency)
                .map(ExchangeRate::getMidRate)
                .orElse(base.midRate());
        BigDecimal mid = fluctuation.next(base.midRate(), previous);
        return new CollectedRate(currency, currency.quoteUnit(), mid,
                sameRatio(mid, base.buyRate(), base.midRate()),
                sameRatio(mid, base.sellRate(), base.midRate()),
                date, RateSource.MOCK);
    }

    /** 마지막 공식 환율. 없으면 설정 기본값과 ±1% 스프레드 */
    private Base base(ForeignCurrency currency) {
        return repository.findLatest(RateSource.KOREAEXIM, currency)
                .map(rate -> new Base(rate.getMidRate(), rate.getBuyRate(), rate.getSellRate()))
                .orElseGet(() -> {
                    BigDecimal mid = defaultMidRates.get(currency);
                    return new Base(mid, mid.multiply(BigDecimal.ONE.add(DEFAULT_SPREAD_RATE)),
                            mid.multiply(BigDecimal.ONE.subtract(DEFAULT_SPREAD_RATE)));
                });
    }

    /** mid × (기준 환율 ÷ 기준 매매기준율). 나눗셈을 마지막에 해서 끝없는 소수가 생기지 않게 한다 */
    private static BigDecimal sameRatio(BigDecimal mid, BigDecimal baseRate, BigDecimal baseMid) {
        return mid.multiply(baseRate).divide(baseMid, MockRateFluctuation.SCALE, RoundingMode.HALF_UP);
    }

    private record Base(BigDecimal midRate, BigDecimal buyRate, BigDecimal sellRate) {
    }
}
