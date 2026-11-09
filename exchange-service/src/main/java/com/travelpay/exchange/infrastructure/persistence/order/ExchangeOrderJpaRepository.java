package com.travelpay.exchange.infrastructure.persistence.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface ExchangeOrderJpaRepository extends JpaRepository<ExchangeOrder, UUID> {

    Optional<ExchangeOrder> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Optional<ExchangeOrder> findByIdAndUserId(UUID id, UUID userId);

    /** 견적 ID로 주문이 있는지. uk_exchange_orders_quote(UNIQUE) 인덱스로 찾는다 */
    boolean existsByQuoteId(UUID quoteId);

    /**
     * 회원 주문 목록. 조건이 null이면 그 조건으로 거르지 않는다. 정렬·페이지는 pageable을 따른다.
     * 환전·재환전은 원화가 어느 쪽에 있는지로 거르므로 보내는(또는 받는) 통화를 KRW로 넘긴다.
     * user_id로 먼저 좁혀 ix_exchange_orders_user_created(user_id, created_at DESC) 인덱스를 탄다.
     */
    @Query(value = """
            select o from ExchangeOrder o
             where o.userId = :userId
               and (:status is null or o.status = :status)
               and (:fromCurrency is null or o.fromCurrency = :fromCurrency)
               and (:toCurrency is null or o.toCurrency = :toCurrency)
            """,
            countQuery = """
            select count(o) from ExchangeOrder o
             where o.userId = :userId
               and (:status is null or o.status = :status)
               and (:fromCurrency is null or o.fromCurrency = :fromCurrency)
               and (:toCurrency is null or o.toCurrency = :toCurrency)
            """)
    Page<ExchangeOrder> findPage(@Param("userId") UUID userId,
                                 @Param("status") ExchangeOrderStatus status,
                                 @Param("fromCurrency") Currency fromCurrency,
                                 @Param("toCurrency") Currency toCurrency,
                                 Pageable pageable);

    /**
     * 확인 횟수를 버전처럼 비교하고, 마지막 확인이 claimableBefore 이전일 때만 원장 호출을 선점한다.
     * 같은 행을 동시에 UPDATE하면 뒤 트랜잭션은 앞이 커밋할 때까지 기다린 뒤 (READ COMMITTED) 최신 행으로 WHERE를 다시 평가하므로
     * 하나만 1행이 된다. 앞 흐름이 선점한 뒤에 읽은 흐름은 횟수가 같아도 확인 시각 조건에 걸려 0행이다(기다리지 않음).
     * 벌크 UPDATE라 updated_at Auditing이 동작하지 않아 시각을 직접 넣는다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExchangeOrder o
               set o.ledgerCheckCount = o.ledgerCheckCount + 1,
                   o.lastLedgerCheckedAt = :now,
                   o.updatedAt = :now
             where o.id = :id
               and o.status = com.travelpay.exchange.domain.order.ExchangeOrderStatus.PROCESSING
               and o.ledgerCheckCount = :seenCheckCount
               and (o.lastLedgerCheckedAt is null or o.lastLedgerCheckedAt <= :claimableBefore)
            """)
    int claimLedgerCheck(@Param("id") UUID id, @Param("seenCheckCount") int seenCheckCount,
                         @Param("now") Instant now, @Param("claimableBefore") Instant claimableBefore);

    /** PROCESSING인 주문만 바꾼다. 이미 확정된 주문은 0행 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExchangeOrder o
               set o.status = :status,
                   o.ledgerTransactionId = :ledgerTransactionId,
                   o.failureReason = :failureReason,
                   o.settledAt = :settledAt,
                   o.updatedAt = :now
             where o.id = :id
               and o.status = com.travelpay.exchange.domain.order.ExchangeOrderStatus.PROCESSING
            """)
    int settleIfProcessing(@Param("id") UUID id,
                           @Param("status") ExchangeOrderStatus status,
                           @Param("ledgerTransactionId") UUID ledgerTransactionId,
                           @Param("failureReason") ExchangeFailureReason failureReason,
                           @Param("settledAt") Instant settledAt,
                           @Param("now") Instant now);

    /** MANUAL_REVIEW인 주문만 관리자 처리 결과로 바꾼다. 이미 확정되었거나 다른 상태인 주문은 0행 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExchangeOrder o
               set o.status = :status,
                   o.ledgerTransactionId = :ledgerTransactionId,
                   o.failureReason = :failureReason,
                   o.settledAt = :settledAt,
                   o.updatedAt = :now
             where o.id = :id
               and o.status = com.travelpay.exchange.domain.order.ExchangeOrderStatus.MANUAL_REVIEW
            """)
    int resolveIfManualReview(@Param("id") UUID id,
                             @Param("status") ExchangeOrderStatus status,
                             @Param("ledgerTransactionId") UUID ledgerTransactionId,
                             @Param("failureReason") ExchangeFailureReason failureReason,
                             @Param("settledAt") Instant settledAt,
                             @Param("now") Instant now);

    /**
     * 관리자용 MANUAL_REVIEW 주문 목록.
     * 인덱스 ix_exchange_orders_manual_review(created_at, order_id)를 타도록 created_at, id 순으로 정렬한다.
     */
    @Query(value = """
            select o from ExchangeOrder o
             where o.status = com.travelpay.exchange.domain.order.ExchangeOrderStatus.MANUAL_REVIEW
               and (:reviewReason is null or exists (
                   select 1 from ExchangeOrderStatusHistory h
                    where h.orderId = o.id
                      and h.toStatus = com.travelpay.exchange.domain.order.ExchangeOrderStatus.MANUAL_REVIEW
                      and h.reason = :reviewReason
               ))
             order by o.createdAt asc, o.id asc
            """,
            countQuery = """
            select count(o) from ExchangeOrder o
             where o.status = com.travelpay.exchange.domain.order.ExchangeOrderStatus.MANUAL_REVIEW
               and (:reviewReason is null or exists (
                   select 1 from ExchangeOrderStatusHistory h
                    where h.orderId = o.id
                      and h.toStatus = com.travelpay.exchange.domain.order.ExchangeOrderStatus.MANUAL_REVIEW
                      and h.reason = :reviewReason
               ))
            """)
    Page<ExchangeOrder> findManualReviews(@Param("reviewReason") String reviewReason, Pageable pageable);

    /**
     * 대기 시간(30초 × 2^min(확인 횟수, 5))이 지난 PROCESSING 주문. 시각은 DB now()가 아니라 서비스 Clock 값을 받는다.
     * 정렬 앞부분은 부분 인덱스 ix_exchange_orders_processing(ledger_check_count, last_ledger_checked_at)과 같다.
     */
    @Query(nativeQuery = true, value = """
            select *
              from exchange_orders
             where status = 'PROCESSING'
               and coalesce(last_ledger_checked_at, requested_at)
                   + interval '30 seconds' * power(2, least(ledger_check_count, 5)) <= :now
             order by ledger_check_count, last_ledger_checked_at nulls first, requested_at
             limit :limit
            """)
    List<ExchangeOrder> findRecoveryTargets(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * 신청 시각이 cutoff 이전인 PROCESSING 주문 수(정체 지표). status = 'PROCESSING' 조건을 그대로 적어
     * 부분 인덱스 ix_exchange_orders_processing(PROCESSING 주문만 담음)을 쓸 수 있게 한다.
     */
    @Query(nativeQuery = true, value = """
            select count(*)
              from exchange_orders
             where status = 'PROCESSING'
               and requested_at <= :cutoff
            """)
    long countProcessingRequestedAtOrBefore(@Param("cutoff") Instant cutoff);

    /** 정체 주문 ID를 신청이 오래된 순으로. 정렬 대상은 PROCESSING 주문뿐이라 작다 */
    @Query(nativeQuery = true, value = """
            select order_id
              from exchange_orders
             where status = 'PROCESSING'
               and requested_at <= :cutoff
             order by requested_at, order_id
             limit :limit
            """)
    List<UUID> findProcessingIdsRequestedAtOrBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** MANUAL_REVIEW 주문 수(대기 지표). 부분 인덱스 ix_exchange_orders_manual_review를 쓸 수 있게 조건을 그대로 적는다 */
    @Query(nativeQuery = true, value = """
            select count(*)
              from exchange_orders
             where status = 'MANUAL_REVIEW'
            """)
    long countManualReview();
}
