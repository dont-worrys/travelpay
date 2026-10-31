package com.travelpay.exchange.domain.order;

import java.util.Optional;
import java.util.UUID;

/** 환전 주문 저장소입니다. 구현은 infrastructure 계층이 담당합니다. */
public interface ExchangeOrderRepository {

    /**
     * 새 주문을 저장하고 바로 DB에 반영합니다. 제약 위반을 저장 시점에 알리기 위해서입니다.
     *
     * @throws DuplicateExchangeOrderException 같은 견적이나 같은 회원·멱등 키의 주문이 이미 있는 경우
     */
    ExchangeOrder save(ExchangeOrder order);

    /** 같은 회원이 같은 멱등 키로 낸 주문입니다. 재요청인지 판단할 때 씁니다. */
    Optional<ExchangeOrder> findByUserIdAndIdempotencyKey(UUID userId, IdempotencyKey idempotencyKey);

    /** 요청자 본인의 주문만 찾습니다. 다른 사람의 주문은 없는 것과 같게 다룹니다. */
    Optional<ExchangeOrder> findByIdAndUserId(UUID orderId, UUID userId);
}
