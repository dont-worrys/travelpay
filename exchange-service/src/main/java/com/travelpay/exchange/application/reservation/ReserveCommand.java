package com.travelpay.exchange.application.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;

/**
 * 예약 환전 요청입니다.
 *
 * @param amount     보내는 금액 (통화 단위, 예: CNY 100.50)
 * @param targetRate 목표 매매기준율 (통화 단위 기준: JPY 100엔당, CNY 1위안당)
 */
public record ReserveCommand(UUID userId, String idempotencyKey, UUID walletId, Currency fromCurrency,
                             Currency toCurrency, BigDecimal amount, BigDecimal targetRate, Instant expiresAt) {
}
