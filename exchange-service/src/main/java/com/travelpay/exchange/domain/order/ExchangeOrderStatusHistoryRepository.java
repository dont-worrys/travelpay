package com.travelpay.exchange.domain.order;

import java.util.Optional;
import java.util.UUID;

/** 환전 주문 상태 이력 저장소입니다. 구현은 infrastructure 계층이 담당합니다. */
public interface ExchangeOrderStatusHistoryRepository {

    ExchangeOrderStatusHistory save(ExchangeOrderStatusHistory history);

    /**
     * 주문이 MANUAL_REVIEW로 빠진 가장 최근 사유를 조회합니다.
     * MANUAL_REVIEW 이력이 없거나 알 수 없는 사유면 empty입니다.
     */
    Optional<ManualReviewReason> findLatestManualReviewReason(UUID orderId);
}
