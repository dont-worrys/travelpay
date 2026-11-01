package com.travelpay.exchange.presentation.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;

/**
 * 환전 주문 응답입니다. 금액과 환율은 부동소수점 오차 없이 통화 단위 문자열로 내려줍니다.
 * 원장 거래 ID·확인 횟수 같은 내부 처리 값은 내려주지 않습니다.
 *
 * @param type          EXCHANGE(원화 → 외화) 또는 RE_EXCHANGE(외화 → 원화)
 * @param status        PROCESSING(처리 중), COMPLETED(완료), FAILED(실패), MANUAL_REVIEW(확인 중)
 * @param failureReason FAILED일 때만 값이 있습니다.
 * @param settledAt     완료·실패가 확정된 시각. 처리 중이면 null입니다.
 */
public record ExchangeOrderResponse(
        UUID orderId,
        String type,
        String status,
        String failureReason,
        UUID quoteId,
        UUID walletId,
        String fromCurrency,
        String fromAmount,
        String toCurrency,
        String toAmount,
        String feeRate,
        String feeAmount,
        String totalDebitAmount,
        String appliedRate,
        int rateUnit,
        LocalDate rateDate,
        Instant requestedAt,
        Instant settledAt
) {

    public static ExchangeOrderResponse from(ExchangeOrder order) {
        ExchangeFailureReason failureReason = order.getFailureReason();
        return new ExchangeOrderResponse(
                order.getId(),
                order.isReExchange() ? "RE_EXCHANGE" : "EXCHANGE",
                order.getStatus().name(),
                failureReason != null ? failureReason.name() : null,
                order.getQuoteId(),
                order.getWalletId(),
                order.getFromCurrency().name(),
                order.fromMoney().amount().toPlainString(),
                order.getToCurrency().name(),
                order.toMoney().amount().toPlainString(),
                plain(order.getFeeRate()),
                order.feeMoney().amount().toPlainString(),
                order.totalDebitMoney().amount().toPlainString(),
                plain(order.getAppliedRate()),
                order.getRateUnit(),
                order.getRateDate(),
                order.getRequestedAt(),
                order.getSettledAt()
        );
    }

    /** DB 자릿수(838.30000000, 0.010000)를 정리해 838.3, 0.01처럼 표기합니다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
