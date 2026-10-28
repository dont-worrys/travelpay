package com.travelpay.exchange.domain.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 수집한 환율 이력 한 건입니다. 환율 값은 unit 단위 외화의 원화 가격입니다.
 * 저장 후에는 바뀌지 않으며, 통화별 최신 행이 현재 환율입니다.
 * 테이블과 제약·인덱스는 Flyway 마이그레이션(V1__create_exchange_rates.sql)이 관리합니다.
 */
@Entity
@Table(name = "exchange_rates")
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

    protected ExchangeRate() {
    }

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

    public UUID getId() {
        return id;
    }

    public ForeignCurrency getCurrency() {
        return currency;
    }

    public String getPriceCurrency() {
        return priceCurrency;
    }

    public int getUnit() {
        return unit;
    }

    public BigDecimal getMidRate() {
        return midRate;
    }

    public BigDecimal getBuyRate() {
        return buyRate;
    }

    public BigDecimal getSellRate() {
        return sellRate;
    }

    public RateSource getSource() {
        return source;
    }

    public LocalDate getRateDate() {
        return rateDate;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }
}
