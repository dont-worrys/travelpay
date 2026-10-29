package com.travelpay.exchange.domain.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.money.MoneyErrorCode;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.shared.BaseTimeEntity;
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
 * 환전 견적입니다. 만료 전까지 보내는 금액·받는 금액·수수료를 보장합니다.
 * 금액은 발급할 때 한 번만 반올림하고, 이후 주문·원장은 이 값을 그대로 복사합니다.
 * 계산 근거(환율 행 ID, 적용 환율, 단위, 고시일, 수수료율)는 견적 행만 보고 알 수 있게 스냅샷으로 저장합니다.
 * 테이블과 제약은 Flyway 마이그레이션(V2__create_exchange_quotes.sql)이 관리합니다.
 */
@Entity
@Table(name = "exchange_quotes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeQuote extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "quote_id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_type", length = 20, nullable = false)
    private QuoteType quoteType;

    @Column(name = "rate_id", nullable = false)
    private UUID rateId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "from_currency", length = 3, nullable = false)
    private Currency fromCurrency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "to_currency", length = 3, nullable = false)
    private Currency toCurrency;

    @Column(name = "from_amount", nullable = false)
    private long fromAmount;

    @Column(name = "to_amount", nullable = false)
    private long toAmount;

    @Column(name = "applied_rate", precision = 18, scale = 8, nullable = false)
    private BigDecimal appliedRate;

    @Column(name = "rate_unit", nullable = false)
    private int rateUnit;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "fee_rate", precision = 7, scale = 6, nullable = false)
    private BigDecimal feeRate;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "total_debit_amount", nullable = false)
    private long totalDebitAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private QuoteStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    private ExchangeQuote(UUID userId, QuoteType quoteType, Money from, Money to, Money fee,
                          ExchangeRate rate, FeeRate feeRate, Instant expiresAt) {
        this.quoteType = quoteType;
        this.rateId = rate.getId();
        this.userId = userId;
        this.fromCurrency = from.currency();
        this.toCurrency = to.currency();
        this.fromAmount = from.minorUnits();
        this.toAmount = to.minorUnits();
        this.appliedRate = rate.getMidRate();
        this.rateUnit = rate.getUnit();
        this.rateDate = rate.getRateDate();
        this.feeRate = feeRate.value();
        this.feeAmount = fee.minorUnits();
        this.totalDebitAmount = from.plus(fee).minorUnits();
        this.status = QuoteStatus.ISSUED;
        this.expiresAt = expiresAt;
    }

    /**
     * 매매기준율과 수수료로 견적을 발급합니다.
     * 받는 금액은 받는 통화 자릿수로 내림하고, 수수료는 보내는 통화로 올림해 보내는 금액에 더합니다.
     *
     * @param rate 원화가 아닌 쪽 외화의 저장된 환율. 최신인지는 호출하는 쪽이 확인합니다.
     * @throws BusinessException 보내는 금액이 범위 밖이거나, 받는 금액이 0이 되는 경우 EXCHANGE_INVALID_AMOUNT
     */
    public static ExchangeQuote issue(UUID userId, QuoteType quoteType, QuoteCurrencies currencies, Money amount,
                                      ExchangeRate rate, QuotePolicy policy, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(quoteType, "quoteType");
        Objects.requireNonNull(rate.getId(), "rate must be saved");
        if (amount.currency() != currencies.from() || rate.getCurrency() != currencies.foreign()) {
            throw new IllegalArgumentException("amount or rate does not match " + currencies);
        }
        policy.checkAmount(amount);
        Money received = rate.convertAtMidRate(amount);
        if (received.isZero()) {
            throw new BusinessException(MoneyErrorCode.INVALID_AMOUNT);
        }
        Money fee = policy.feeRate().feeFor(amount);
        return new ExchangeQuote(userId, quoteType, amount, received, fee, rate, policy.feeRate(),
                now.plus(policy.ttl()));
    }

    /** 아직 주문에 쓰지 않았고 만료 시각이 지났으면 만료입니다. 만료 시각과 같은 순간부터 만료입니다. */
    public boolean isExpired(Instant now) {
        return status == QuoteStatus.ISSUED && !now.isBefore(expiresAt);
    }

    public Money fromMoney() {
        return new Money(fromCurrency, fromAmount);
    }

    public Money toMoney() {
        return new Money(toCurrency, toAmount);
    }

    public Money feeMoney() {
        return new Money(fromCurrency, feeAmount);
    }

    public Money totalDebitMoney() {
        return new Money(fromCurrency, totalDebitAmount);
    }
}
