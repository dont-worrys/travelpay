package com.travelpay.exchange.presentation.order;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;

/**
 * 환전 주문 목록의 한 줄입니다. 목록 화면에 필요한 값만 담고, 환율·수수료·견적 ID 같은 근거는 상세 조회에서 내려줍니다.
 * 금액은 상세 조회와 같이 통화 단위 문자열입니다.
 *
 * @param type          EXCHANGE(원화 → 외화) 또는 RE_EXCHANGE(외화 → 원화)
 * @param status        PROCESSING(처리 중), COMPLETED(완료), FAILED(실패), MANUAL_REVIEW(확인 중)
 * @param failureReason FAILED일 때만 값이 있습니다.
 * @param settledAt     완료·실패가 확정된 시각. 처리 중이면 null입니다.
 */
public record ExchangeOrderSummaryResponse(
        UUID orderId,
        String type,
        String status,
        String failureReason,
        String fromCurrency,
        String fromAmount,
        String toCurrency,
        String toAmount,
        String totalDebitAmount,
        Instant requestedAt,
        Instant settledAt
) {

    public static ExchangeOrderSummaryResponse from(ExchangeOrder order) {
        ExchangeFailureReason failureReason = order.getFailureReason();
        return new ExchangeOrderSummaryResponse(
                order.getId(),
                order.type().name(),
                order.getStatus().name(),
                failureReason != null ? failureReason.name() : null,
                order.getFromCurrency().name(),
                order.fromMoney().amount().toPlainString(),
                order.getToCurrency().name(),
                order.toMoney().amount().toPlainString(),
                order.totalDebitMoney().amount().toPlainString(),
                order.getRequestedAt(),
                order.getSettledAt()
        );
    }
}
