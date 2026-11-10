package com.travelpay.exchange.application.order;

import java.util.UUID;

/**
 * 환전 신청입니다.
 *
 * @param idempotencyKey 요청 헤더의 멱등 키 원문. 형식은 서비스에서 확인합니다.
 */
public record PlaceOrderCommand(UUID userId, String idempotencyKey, UUID quoteId, UUID walletId) {
}
