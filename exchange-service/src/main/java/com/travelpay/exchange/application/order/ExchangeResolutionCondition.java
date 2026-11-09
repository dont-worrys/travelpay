package com.travelpay.exchange.application.order;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 처리 이력 목록의 거름 조건입니다. null인 조건으로는 거르지 않습니다.
 *
 * @param actorId 처리한 관리자 ID
 * @param orderId 주문 ID
 * @param from    처리 시각 시작 (포함)
 * @param to      처리 시각 끝 (제외)
 */
public record ExchangeResolutionCondition(UUID actorId, UUID orderId, Instant from, Instant to) {
}
