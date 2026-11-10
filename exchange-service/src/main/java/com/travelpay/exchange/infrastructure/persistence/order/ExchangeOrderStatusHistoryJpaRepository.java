package com.travelpay.exchange.infrastructure.persistence.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 관리자(MASTER) 처리 이력 목록. 조건이 null이면 그 조건으로 거르지 않는다.
     * 부분 인덱스 ix_exchange_order_status_histories_master(created_at DESC, history_id DESC)와 같은 순서로 정렬한다.
     */
    @Query(value = """
            select h from ExchangeOrderStatusHistory h
             where h.changedByType = com.travelpay.exchange.domain.order.ChangedByType.MASTER
               and (:actorId is null or h.changedById = :actorId)
               and (:orderId is null or h.orderId = :orderId)
               and (cast(:fromTime as Instant) is null or h.createdAt >= :fromTime)
               and (cast(:toTime as Instant) is null or h.createdAt < :toTime)
             order by h.createdAt desc, h.id desc
            """,
            countQuery = """
            select count(h) from ExchangeOrderStatusHistory h
             where h.changedByType = com.travelpay.exchange.domain.order.ChangedByType.MASTER
               and (:actorId is null or h.changedById = :actorId)
               and (:orderId is null or h.orderId = :orderId)
               and (cast(:fromTime as Instant) is null or h.createdAt >= :fromTime)
               and (cast(:toTime as Instant) is null or h.createdAt < :toTime)
            """)
    Page<ExchangeOrderStatusHistory> findResolutions(@Param("actorId") UUID actorId,
                                                     @Param("orderId") UUID orderId,
                                                     @Param("fromTime") Instant fromTime,
                                                     @Param("toTime") Instant toTime,
                                                     Pageable pageable);
}
