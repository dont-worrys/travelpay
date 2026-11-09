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
import lombok.Getter;

/**
 * 환전 견적입니다. 만료 전까지 보내는 금액·받는 금액·수수료를 보장합니다.
 * 금액은 발급할 때 한 번만 반올림하고, 이후 주문·원장은 이 값을 그대로 복사합니다.
 * 계산 근거(환율 행 ID, 적용 환율, 단위, 고시일, 수수료율)는 견적만 보고 알 수 있게 스냅샷으로 담습니다.
 *
 * 견적은 Redis에 저장합니다(#223). 그래서 JPA 엔티티가 아니고, ID와 발급 시각을 발급할 때 직접 정합니다.
 * 상태(사용 여부)는 저장하지 않습니다. 한 번만 쓰는 것은 주문의 UNIQUE(quote_id)가 보장하고,
 * 조회 응답의 상태는 그때 계산합니다.
 */
@Getter
public class ExchangeQuote {

    private final UUID id;
    private final QuoteType quoteType;
    private final UUID rateId;
    private final UUID userId;
    private final Currency fromCurrency;
    private final Currency toCurrency;
    private final long fromAmount;
    private final long toAmount;
    private final BigDecimal appliedRate;
    private final int rateUnit;
    private final LocalDate rateDate;
    private final BigDecimal feeRate;
    private final long feeAmount;
    private final long totalDebitAmount;
    private final Instant issuedAt;
    private final Instant expiresAt;

    private ExchangeQuote(UUID id, QuoteType quoteType, UUID rateId, UUID userId,
                          Currency fromCurrency, Currency toCurrency, long fromAmount, long toAmount,
                          BigDecimal appliedRate, int rateUnit, LocalDate rateDate,
                          BigDecimal feeRate, long feeAmount, long totalDebitAmount,
                          Instant issuedAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.quoteType = Objects.requireNonNull(quoteType, "quoteType");
        this.rateId = Objects.requireNonNull(rateId, "rateId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.fromCurrency = Objects.requireNonNull(fromCurrency, "fromCurrency");
        this.toCurrency = Objects.requireNonNull(toCurrency, "toCurrency");
        this.fromAmount = fromAmount;
        this.toAmount = toAmount;
        this.appliedRate = Objects.requireNonNull(appliedRate, "appliedRate");
        this.rateUnit = rateUnit;
        this.rateDate = Objects.requireNonNull(rateDate, "rateDate");
        this.feeRate = Objects.requireNonNull(feeRate, "feeRate");
        this.feeAmount = feeAmount;
        this.totalDebitAmount = totalDebitAmount;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * 매매기준율과 수수료로 견적을 발급합니다. ID는 여기서 만들고, 발급 시각은 now입니다.
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
        return new ExchangeQuote(UUID.randomUUID(), quoteType, rate.getId(), userId,
                amount.currency(), received.currency(), amount.minorUnits(), received.minorUnits(),
                rate.getMidRate(), rate.getUnit(), rate.getRateDate(),
                policy.feeRate().value(), fee.minorUnits(), amount.plus(fee).minorUnits(),
                now, now.plus(policy.ttl()));
    }

    /**
     * 저장소에서 읽은 값으로 견적을 되살립니다. 발급할 때 계산한 값을 그대로 쓰고 다시 계산하지 않습니다.
     */
    public static ExchangeQuote restore(UUID id, QuoteType quoteType, UUID rateId, UUID userId,
                                        Currency fromCurrency, Currency toCurrency, long fromAmount, long toAmount,
                                        BigDecimal appliedRate, int rateUnit, LocalDate rateDate,
                                        BigDecimal feeRate, long feeAmount, long totalDebitAmount,
                                        Instant issuedAt, Instant expiresAt) {
        return new ExchangeQuote(id, quoteType, rateId, userId, fromCurrency, toCurrency, fromAmount, toAmount,
                appliedRate, rateUnit, rateDate, feeRate, feeAmount, totalDebitAmount, issuedAt, expiresAt);
    }

    /** 만료 시각이 지났으면 만료입니다. 만료 시각과 같은 순간부터 만료입니다. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
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
