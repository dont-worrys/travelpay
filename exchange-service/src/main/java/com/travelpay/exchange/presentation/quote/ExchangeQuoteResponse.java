package com.travelpay.exchange.presentation.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.travelpay.exchange.application.quote.ExchangeQuoteView;
import com.travelpay.exchange.domain.quote.ExchangeQuote;

/**
 * 견적 응답입니다. 금액과 환율은 부동소수점 오차 없이 전달하도록 통화 단위 문자열로 내려줍니다.
 * 적용 환율은 rateUnit 단위 외화의 원화 가격입니다. (예: JPY rateUnit=100이면 100엔당 가격)
 *
 * @param totalDebitAmount 보내는 통화로 지갑에서 빠질 금액 (보내는 금액 + 수수료)
 * @param status           ISSUED, USED, EXPIRED. EXPIRED는 저장 상태가 아니라 조회 시각 기준 판단입니다.
 */
public record ExchangeQuoteResponse(
        UUID quoteId,
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
        String status,
        Instant expiresAt,
        Instant createdAt
) {

    /** 최소 단위 금액을 통화 단위 문자열로 바꿉니다. 조회 시각에 만료됐으면 저장 상태와 관계없이 EXPIRED입니다. */
    public static ExchangeQuoteResponse from(ExchangeQuoteView view) {
        ExchangeQuote quote = view.quote();
        return new ExchangeQuoteResponse(
                quote.getId(),
                quote.getFromCurrency().name(),
                quote.fromMoney().amount().toPlainString(),
                quote.getToCurrency().name(),
                quote.toMoney().amount().toPlainString(),
                plain(quote.getFeeRate()),
                quote.feeMoney().amount().toPlainString(),
                quote.totalDebitMoney().amount().toPlainString(),
                plain(quote.getAppliedRate()),
                quote.getRateUnit(),
                quote.getRateDate(),
                view.expired() ? "EXPIRED" : quote.getStatus().name(),
                quote.getExpiresAt(),
                quote.getCreatedAt()
        );
    }

    /** DB 자릿수(838.30000000, 0.010000)를 정리해 838.3, 0.01처럼 표기합니다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
