package com.travelpay.exchange.presentation.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.application.reservation.ReserveCommand;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.rate.ExchangeRateErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 예약 환전 요청입니다. 통화·금액 형식은 견적 요청과 같습니다.
 *
 * @param walletId   체결할 지갑 (본인 지갑)
 * @param amount     보내는 금액 (통화 단위, 예: KRW "100000", CNY "100.50")
 * @param targetRate 목표 매매기준율 (통화 단위 기준: JPY 100엔당, CNY 1위안당, 소수 둘째 자리까지)
 * @param expiresAt  만료 시각 (UTC ISO-8601, 지금 이후 최대 30일)
 */
public record ExchangeReservationRequest(
        @NotNull(message = "지갑 ID를 입력해 주세요.") UUID walletId,
        @NotBlank String fromCurrency,
        @NotBlank String toCurrency,
        @NotNull BigDecimal amount,
        @NotNull(message = "목표 환율을 입력해 주세요.") BigDecimal targetRate,
        @NotNull(message = "만료 시각을 입력해 주세요.") Instant expiresAt
) {

    /** 지원하지 않는 통화 코드면 EXCHANGE_UNSUPPORTED_CURRENCY 오류입니다. */
    public ReserveCommand toCommand(UUID userId, String idempotencyKey) {
        return new ReserveCommand(userId, idempotencyKey, walletId, currency(fromCurrency), currency(toCurrency),
                amount, targetRate, expiresAt);
    }

    private static Currency currency(String code) {
        return Currency.find(code)
                .orElseThrow(() -> new BusinessException(ExchangeRateErrorCode.UNSUPPORTED_CURRENCY));
    }
}
