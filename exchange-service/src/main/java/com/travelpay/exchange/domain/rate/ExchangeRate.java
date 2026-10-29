package com.travelpay.exchange.domain.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 수집한 환율 이력 한 건입니다. 환율 값은 unit 단위 외화의 원화 가격입니다.
 * 저장 후에는 바뀌지 않으며, 통화별 최신 행이 현재 환율입니다.
 * 테이블과 제약·인덱스는 Flyway 마이그레이션(V1__create_exchange_rates.sql)이 관리합니다.
 */
@Entity
@Table(name = "exchange_rates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeRate {

    private static final String PRICE_CURRENCY = "KRW";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "exchange_rate_id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "target_currency", length = 3, nullable = false)
    private ForeignCurrency currency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "price_currency", length = 3, nullable = false)
    private String priceCurrency;

    @Column(name = "unit", nullable = false)
    private int unit;

    @Column(name = "mid_rate", precision = 18, scale = 8, nullable = false)
    private BigDecimal midRate;

    @Column(name = "buy_rate", precision = 18, scale = 8, nullable = false)
    private BigDecimal buyRate;

    @Column(name = "sell_rate", precision = 18, scale = 8, nullable = false)
    private BigDecimal sellRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    private RateSource source;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    private ExchangeRate(CollectedRate rate, Instant collectedAt) {
        this.currency = rate.currency();
        this.priceCurrency = PRICE_CURRENCY;
        this.unit = rate.unit();
        this.midRate = rate.midRate();
        this.buyRate = rate.buyRate();
        this.sellRate = rate.sellRate();
        this.source = rate.source();
        this.rateDate = rate.rateDate();
        this.collectedAt = Objects.requireNonNull(collectedAt, "collectedAt");
    }

    public static ExchangeRate collected(CollectedRate rate, Instant collectedAt) {
        return new ExchangeRate(Objects.requireNonNull(rate, "rate"), collectedAt);
    }

    /**
     * 매매기준율로 원화와 이 환율의 외화 사이를 환산합니다. 받는 금액은 받는 통화 자릿수로 내림합니다.
     * 원화 → 외화는 {@code 원화 × unit ÷ 환율}, 외화 → 원화는 {@code 외화 × 환율 ÷ unit}입니다.
     *
     * @throws IllegalArgumentException 금액이 원화나 이 환율의 외화가 아닌 경우
     */
    public Money convertAtMidRate(Money amount) {
        Currency foreign = currency.currency();
        BigDecimal value = amount.amount();
        BigDecimal quoteUnit = BigDecimal.valueOf(unit);
        if (amount.currency() == Currency.KRW) {
            return Money.of(foreign, value.multiply(quoteUnit)
                    .divide(midRate, foreign.fractionDigits(), RoundingMode.DOWN));
        }
        if (amount.currency() == foreign) {
            return Money.of(Currency.KRW, value.multiply(midRate)
                    .divide(quoteUnit, Currency.KRW.fractionDigits(), RoundingMode.DOWN));
        }
        throw new IllegalArgumentException(amount.currency() + " cannot be converted with " + currency + " rate");
    }
}
