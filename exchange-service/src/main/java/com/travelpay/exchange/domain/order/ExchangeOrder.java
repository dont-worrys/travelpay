package com.travelpay.exchange.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
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
 * 환전·재환전 주문입니다. 견적의 환율 근거와 금액을 그대로 복사한 스냅샷이라, 견적이 사라져도
 * 이 행만으로 어떤 환율·수수료로 얼마를 주고받았는지 증빙합니다. 환전과 재환전은 받는 통화로 구분합니다.
 *
 * JPA로는 INSERT만 합니다. 모든 컬럼이 {@code updatable = false}라 엔티티 값을 바꿔도 UPDATE되지 않습니다.
 * 원장 결과 반영처럼 상태가 바뀌는 일은 저장소의 조건부 UPDATE({@code WHERE status = 'PROCESSING'})로만 합니다.
 * 같은 주문을 여러 흐름(첫 요청·재요청·복구 스케줄러)이 동시에 반영해도 한 번만 바뀌게 하려는 것입니다.
 *
 * 테이블과 제약은 Flyway 마이그레이션(V3__create_exchange_orders.sql)이 관리합니다.
 */
@Entity
@Table(name = "exchange_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeOrder extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id", updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "idempotency_key", length = 100, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "quote_issued_at", nullable = false, updatable = false)
    private Instant quoteIssuedAt;

    @Column(name = "quote_expires_at", nullable = false, updatable = false)
    private Instant quoteExpiresAt;

    @Column(name = "rate_id", nullable = false, updatable = false)
    private UUID rateId;

    @Column(name = "rate_date", nullable = false, updatable = false)
    private LocalDate rateDate;

    @Column(name = "applied_rate", precision = 18, scale = 8, nullable = false, updatable = false)
    private BigDecimal appliedRate;

    @Column(name = "rate_unit", nullable = false, updatable = false)
    private int rateUnit;

    @Column(name = "fee_rate", precision = 7, scale = 6, nullable = false, updatable = false)
    private BigDecimal feeRate;

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

    @Column(name = "fee_amount", nullable = false, updatable = false)
    private long feeAmount;

    @Column(name = "to_amount", nullable = false, updatable = false)
    private long toAmount;

    @Column(name = "total_debit_amount", nullable = false, updatable = false)
    private long totalDebitAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false, updatable = false)
    private ExchangeOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30, updatable = false)
    private ExchangeFailureReason failureReason;

    @Column(name = "ledger_transaction_id", updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "settled_at", updatable = false)
    private Instant settledAt;

    @Column(name = "ledger_check_count", nullable = false, updatable = false)
    private int ledgerCheckCount;

    @Column(name = "last_ledger_checked_at", updatable = false)
    private Instant lastLedgerCheckedAt;

    private ExchangeOrder(ExchangeQuote quote, UUID walletId, IdempotencyKey idempotencyKey, Instant requestedAt) {
        this.userId = quote.getUserId();
        this.walletId = walletId;
        this.idempotencyKey = idempotencyKey.value();
        this.quoteId = quote.getId();
        this.quoteIssuedAt = quote.getCreatedAt();
        this.quoteExpiresAt = quote.getExpiresAt();
        this.rateId = quote.getRateId();
        this.rateDate = quote.getRateDate();
        this.appliedRate = quote.getAppliedRate();
        this.rateUnit = quote.getRateUnit();
        this.feeRate = quote.getFeeRate();
        this.fromCurrency = quote.getFromCurrency();
        this.toCurrency = quote.getToCurrency();
        this.fromAmount = quote.getFromAmount();
        this.feeAmount = quote.getFeeAmount();
        this.toAmount = quote.getToAmount();
        this.totalDebitAmount = quote.getTotalDebitAmount();
        this.status = ExchangeOrderStatus.PROCESSING;
        this.requestedAt = requestedAt;
        this.ledgerCheckCount = 0;
    }

    /**
     * 견적으로 환전을 신청합니다. 견적 값을 계산 없이 복사하고 원장 결과를 기다리는 PROCESSING으로 시작합니다.
     * 견적 소유자 확인은 견적을 조회하는 쪽이, 지갑 소유·상태 확인은 신청하는 쪽이 합니다.
     * 견적의 사용 여부(ISSUED/USED)는 보지 않습니다. 한 번만 사용되는 것은 주문의 UNIQUE(quote_id)가 보장합니다.
     *
     * @param requestedAt 신청 시각. 이 시각으로 만료를 판단하고 그대로 저장해 유효 기간 안의 신청이었음을 증빙합니다.
     * @throws BusinessException 신청 시각이 견적 만료 시각과 같거나 늦으면 EXCHANGE_QUOTE_EXPIRED
     */
    public static ExchangeOrder place(ExchangeQuote quote, UUID walletId, IdempotencyKey idempotencyKey,
                                      Instant requestedAt) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(quote.getId(), "quote must be saved");
        Objects.requireNonNull(quote.getCreatedAt(), "quote must have issued time");
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (!requestedAt.isBefore(quote.getExpiresAt())) {
            throw new BusinessException(ExchangeOrderErrorCode.QUOTE_EXPIRED);
        }
        return new ExchangeOrder(quote, walletId, idempotencyKey, requestedAt);
    }

    /** 주문 생성 이력(없음 → PROCESSING, 회원)입니다. 주문이 저장돼 ID가 생긴 뒤에 만듭니다. */
    public ExchangeOrderStatusHistory placedHistory() {
        return ExchangeOrderStatusHistory.of(requireId(), null, ExchangeOrderStatus.PROCESSING, null,
                ChangedByType.USER);
    }

    /** 같은 멱등 키로 다시 온 신청이 처음과 같은 견적·지갑인지 확인합니다. 다르면 키를 다른 신청에 재사용한 것입니다. */
    public boolean isSameRequest(UUID quoteId, UUID walletId) {
        return this.quoteId.equals(quoteId) && this.walletId.equals(walletId);
    }

    /** 외화를 원화로 바꾸는 재환전이면 true입니다. 한쪽은 항상 원화라 받는 통화만 보면 됩니다. */
    public boolean isReExchange() {
        return toCurrency == Currency.KRW;
    }

    public Money fromMoney() {
        return new Money(fromCurrency, fromAmount);
    }

    public Money feeMoney() {
        return new Money(fromCurrency, feeAmount);
    }

    public Money toMoney() {
        return new Money(toCurrency, toAmount);
    }

    public Money totalDebitMoney() {
        return new Money(fromCurrency, totalDebitAmount);
    }

    private UUID requireId() {
        if (id == null) {
            throw new IllegalStateException("order must be saved before creating history");
        }
        return id;
    }
}
