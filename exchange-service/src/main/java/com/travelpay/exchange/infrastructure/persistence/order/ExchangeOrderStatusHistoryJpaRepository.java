package com.travelpay.exchange.infrastructure.persistence.order;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExchangeOrderStatusHistoryJpaRepository extends JpaRepository<ExchangeOrderStatusHistory, UUID> {

    interface ManualReviewReasonProjection {
        UUID getOrderId();
        String getReason();
    }

    /** 주문이 MANUAL_REVIEW로 빠진 이력의 사유를 최신순으로 조회합니다. */
    @Query("""
            select h.reason
              from ExchangeOrderStatusHistory h
             where h.orderId = :orderId
               and h.toStatus = com.travelpay.exchange.domain.order.ExchangeOrderStatus.MANUAL_REVIEW
             order by h.createdAt desc
            """)
    List<String> findManualReviewReasons(@Param("orderId") UUID orderId);

    /** 여러 주문의 MANUAL_REVIEW 진입 사유를 한 번에 조회합니다. */
    @Query("""
            select h.orderId as orderId, h.reason as reason
              from ExchangeOrderStatusHistory h
             where h.orderId in :orderIds
               and h.toStatus = com.travelpay.exchange.domain.order.ExchangeOrderStatus.MANUAL_REVIEW
             order by h.createdAt desc
            """)
    List<ManualReviewReasonProjection> findManualReviewReasonsByOrderIds(@Param("orderIds") Collection<UUID> orderIds);
}
