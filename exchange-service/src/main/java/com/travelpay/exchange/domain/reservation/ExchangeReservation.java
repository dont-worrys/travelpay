package com.travelpay.exchange.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import com.travelpay.exchange.domain.quote.QuoteType;
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
 * 예약 환전입니다. 목표 환율(매매기준율)에 닿으면 보통 환전과 같은 주문을 만들어 체결합니다. 예약할 때는 돈이 움직이지 않습니다.
 *
 * 조건 방향은 통화쌍으로 정해집니다.
 * - 원화 → 외화(외화를 삼): 환율이 목표 이하가 되면 체결 (싸게 사기)
 * - 외화 → 원화(외화를 팖): 환율이 목표 이상이 되면 체결 (비싸게 팔기)
 * 목표 환율은 견적과 같은 매매기준율, 같은 단위(JPY 100엔당, CNY 1위안당)입니다.
 *
 * 주문처럼 JPA로는 INSERT만 합니다. 모든 컬럼이 updatable = false라, 상태(체결·취소·만료)는 저장소의 조건부 UPDATE
 * (WHERE status = 'WAITING')로만 바꿉니다. 체결과 취소가 겹쳐도 한쪽만 반영되게 하려는 것입니다.
 * 테이블과 제약은 Flyway 마이그레이션(V7__create_exchange_reservations.sql)이 관리합니다.
 */
@Entity
@Table(name = "exchange_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeReservation extends BaseTimeEntity {

    /** 목표 환율의 소수 자릿수. 수집하는 환율(매매기준율)이 소수 둘째 자리라 그보다 자세한 값은 의미가 없습니다. */
    static final int TARGET_RATE_SCALE = 2;

    /** 체결로 만드는 주문의 멱등 키 앞부분 */
    static final String ORDER_KEY_PREFIX = "reservation:";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reservation_id", updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "idempotency_key", length = 100, nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "from_currency", length = 3, nullable = false, updatable = false)
    private Currency fromCurrency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "to_currency", length = 3, nullable = false, updatable = false)
    private Currency toCurrency;

    @Column(name = "from_amount", nullable = false, updatable = false)
    private long fromAmount;

    @Column(name = "target_rate", precision = 18, scale = 8, nullable = false, updatable = false)
    private BigDecimal targetRate;

    @Column(name = "rate_unit", nullable = false, updatable = false)
    private int rateUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false, updatable = false)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "executed_at", updatable = false)
    private Instant executedAt;

    private ExchangeReservation(UUID userId, UUID walletId, IdempotencyKey idempotencyKey, Money amount,
                                Currency toCurrency, BigDecimal targetRate, int rateUnit, Instant expiresAt) {
        this.userId = userId;
        this.walletId = walletId;
        this.idempotencyKey = idempotencyKey.value();
        this.fromCurrency = amount.currency();
        this.toCurrency = toCurrency;
        this.fromAmount = amount.minorUnits();
        this.targetRate = targetRate;
        this.rateUnit = rateUnit;
        this.status = ReservationStatus.WAITING;
        this.expiresAt = expiresAt;
    }

    /**
     * 예약을 만듭니다. 보내는 금액은 견적과 같은 1회 한도로 검사합니다(체결 때 같은 금액으로 견적을 계산하므로).
     *
     * @param targetRate 목표 매매기준율. 통화 단위(JPY 100엔당, CNY 1위안당) 기준이며 소수 둘째 자리까지
     * @param expiresAt  만료 시각. 지금 이후이고 최대 예약 기간 안이어야 합니다
     * @throws BusinessException 금액이 한도 밖·자릿수 초과(EXCHANGE_INVALID_AMOUNT), 목표 환율 형식 오류
     *                           (EXCHANGE_RESERVATION_INVALID_TARGET_RATE), 만료 시각 범위 밖(EXCHANGE_RESERVATION_INVALID_EXPIRY)
     */
    public static ExchangeReservation reserve(UUID userId, UUID walletId, IdempotencyKey idempotencyKey,
                                              QuoteCurrencies currencies, Money amount, BigDecimal targetRate,
                                              Instant expiresAt, QuotePolicy quotePolicy, ReservationPolicy policy,
                                              Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(currencies, "currencies");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(now, "now");
        if (amount.currency() != currencies.from()) {
            throw new IllegalArgumentException("amount currency must be " + currencies.from());
        }
        quotePolicy.checkAmount(amount);
        if (targetRate == null || targetRate.signum() <= 0 || targetRate.stripTrailingZeros().scale() > TARGET_RATE_SCALE) {
            throw new BusinessException(ExchangeReservationErrorCode.INVALID_TARGET_RATE);
        }
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(policy.maxPeriod()))) {
            throw new BusinessException(ExchangeReservationErrorCode.INVALID_EXPIRY);
        }
        return new ExchangeReservation(userId, walletId, idempotencyKey, amount, currencies.to(),
                targetRate, currencies.foreign().quoteUnit(), expiresAt);
    }

    /**
     * 이 환율이면 체결할지 판단합니다. 대기 중이 아니거나 통화가 다르면 false입니다.
     * 원화 → 외화는 환율이 목표 이하, 외화 → 원화는 목표 이상일 때 체결합니다(같은 값이면 체결).
     */
    public boolean isTriggeredBy(ExchangeRate rate) {
        Objects.requireNonNull(rate, "rate");
        if (status != ReservationStatus.WAITING || rate.getCurrency().currency() != foreignCurrency()) {
            return false;
        }
        if (rate.getUnit() != rateUnit) {
            throw new IllegalStateException("환율 단위가 예약과 다릅니다: rate=" + rate.getUnit() + ", reservation=" + rateUnit);
        }
        int compared = rate.getMidRate().compareTo(targetRate);
        return buysForeign() ? compared <= 0 : compared >= 0;
    }

    /**
     * 같은 멱등 키로 다시 온 예약 요청이 처음과 같은 내용인지 확인합니다. 다르면 키를 다른 예약에 재사용한 것입니다.
     * 목표 환율은 값으로 비교합니다(830과 830.00은 같음).
     */
    public boolean isSameRequest(UUID walletId, QuoteCurrencies currencies, Money amount, BigDecimal targetRate,
                                 Instant expiresAt) {
        return this.walletId.equals(walletId)
                && fromCurrency == currencies.from()
                && toCurrency == currencies.to()
                && fromMoney().equals(amount)
                && targetRate != null && this.targetRate.compareTo(targetRate) == 0
                && this.expiresAt.equals(expiresAt);
    }

    /** 만료 시각과 같은 순간부터 만료입니다. */
    /**
     * 조건에 닿은 환율로 주문을 만듭니다. 사용자 견적 대신 이 환율로 체결용 견적(RESERVATION)을 메모리에서 만들고,
     * 보통 환전과 같은 방법으로 견적 값을 주문에 복사합니다. 그래서 주문 테이블과 원장 처리는 보통 환전과 같습니다.
     * 체결용 견적은 저장하지 않습니다. 주문에 견적 값이 그대로 남기 때문입니다.
     * 주문 멱등 키는 "reservation:{예약 ID}"라 같은 예약으로 주문을 두 번 만들 수 없습니다.
     * 예약 상태는 바꾸지 않습니다. 저장소의 조건부 UPDATE(markExecuted)가 주문 ID와 함께 바꿉니다.
     *
     * @throws IllegalStateException 대기 중이 아니거나, 이 환율로 조건에 닿지 않았거나, 만료됨 (부르는 쪽이 먼저 확인할 일)
     * @throws BusinessException     체결 시점 한도로 금액이 범위 밖(EXCHANGE_INVALID_AMOUNT)
     */
    public ExchangeOrder placeOrder(ExchangeRate rate, QuotePolicy quotePolicy, Instant now) {
        requireId();
        if (!isTriggeredBy(rate) || isExpired(now)) {
            throw new IllegalStateException("체결할 수 없는 예약입니다: reservationId=" + id + ", status=" + status);
        }
        ExchangeQuote quote = ExchangeQuote.issue(userId, QuoteType.RESERVATION, new QuoteCurrencies(fromCurrency, toCurrency),
                fromMoney(), rate, quotePolicy, now);
        return ExchangeOrder.place(quote, walletId, orderIdempotencyKey(), now);
    }

    /** 이 예약으로 만드는 주문의 멱등 키입니다. */
    public IdempotencyKey orderIdempotencyKey() {
        return new IdempotencyKey(ORDER_KEY_PREFIX + requireId());
    }

    private UUID requireId() {
        return Objects.requireNonNull(id, "reservation must be saved");
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 원화로 외화를 사는 예약이면 true(환전), 외화를 원화로 파는 예약이면 false(재환전)입니다. */
    public boolean buysForeign() {
        return fromCurrency == Currency.KRW;
    }

    public Money fromMoney() {
        return new Money(fromCurrency, fromAmount);
    }

    private Currency foreignCurrency() {
        return buysForeign() ? toCurrency : fromCurrency;
    }
}
