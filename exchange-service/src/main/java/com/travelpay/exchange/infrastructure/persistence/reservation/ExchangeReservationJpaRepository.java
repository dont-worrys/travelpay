package com.travelpay.exchange.infrastructure.persistence.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface ExchangeReservationJpaRepository extends JpaRepository<ExchangeReservation, UUID> {

    Optional<ExchangeReservation> findByIdAndUserId(UUID id, UUID userId);

    Optional<ExchangeReservation> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /** 회원의 대기 예약 수. user_id로 먼저 좁혀 ix_exchange_reservations_user_created 인덱스를 탄다 */
    @Query("""
            select count(r) from ExchangeReservation r
             where r.userId = :userId
               and r.status = com.travelpay.exchange.domain.reservation.ReservationStatus.WAITING
            """)
    long countWaitingByUserId(@Param("userId") UUID userId);

    /** 회원 예약 목록. status가 null이면 모든 상태. 정렬·페이지는 pageable을 따른다 */
    @Query(value = """
            select r from ExchangeReservation r
             where r.userId = :userId
               and (:status is null or r.status = :status)
            """,
            countQuery = """
            select count(r) from ExchangeReservation r
             where r.userId = :userId
               and (:status is null or r.status = :status)
            """)
    Page<ExchangeReservation> findPage(@Param("userId") UUID userId, @Param("status") ReservationStatus status,
                                       Pageable pageable);

    /**
     * 본인의 대기 예약만 취소한다. 이미 체결·취소·만료됐거나 다른 회원의 예약이면 0행.
     * 같은 행을 동시에 바꾸면 뒤 트랜잭션은 앞이 커밋할 때까지 기다린 뒤 최신 행으로 WHERE를 다시 평가하므로(READ COMMITTED) 하나만 1행이다.
     * 벌크 UPDATE라 updated_at Auditing이 동작하지 않아 시각을 직접 넣는다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExchangeReservation r
               set r.status = com.travelpay.exchange.domain.reservation.ReservationStatus.CANCELED,
                   r.updatedAt = :now
             where r.id = :id
               and r.userId = :userId
               and r.status = com.travelpay.exchange.domain.reservation.ReservationStatus.WAITING
            """)
    int cancelIfWaiting(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * 체결 조건에 닿은 대기 예약 ID. 사는 쪽·파는 쪽을 따로 조회해 합친다.
     * 각 조회가 (통화쌍 같음 + 목표 환율 범위)라 ix_exchange_reservations_waiting_match 부분 인덱스를 탄다.
     * OR 하나로 쓰면 통화쌍이 둘이라 인덱스 범위가 깨질 수 있어 UNION ALL로 나눈다(두 조건은 겹치지 않음).
     * 부분 인덱스 조건(status = 'WAITING')과 문자 그대로 같아야 플래너가 그 인덱스를 고르므로 네이티브 SQL로 쓴다.
     */
    @Query(value = """
            SELECT m.reservation_id FROM (
                SELECT reservation_id, created_at FROM exchange_reservations
                 WHERE status = 'WAITING' AND from_currency = 'KRW' AND to_currency = :foreign
                   AND target_rate >= :midRate AND expires_at > :now
                UNION ALL
                SELECT reservation_id, created_at FROM exchange_reservations
                 WHERE status = 'WAITING' AND from_currency = :foreign AND to_currency = 'KRW'
                   AND target_rate <= :midRate AND expires_at > :now
            ) m
            ORDER BY m.created_at, m.reservation_id
            """, nativeQuery = true)
    List<UUID> findTriggeredWaitingIds(@Param("foreign") String foreign, @Param("midRate") BigDecimal midRate,
                                       @Param("now") Instant now);

    /**
     * 대기 예약을 잠그고 읽는다. SKIP LOCKED라 다른 트랜잭션이 잠근 행은 기다리지 않고 건너뛴다(빈 결과).
     * 여러 인스턴스가 같은 환율로 같은 예약을 찾아도 한 곳만 체결하고, 나머지는 바로 다음 예약으로 넘어간다.
     */
    @Query(value = """
            SELECT * FROM exchange_reservations
             WHERE reservation_id = :id AND status = 'WAITING'
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ExchangeReservation> lockWaiting(@Param("id") UUID id);

    /**
     * 대기 예약을 체결로 바꾸고 주문 ID·체결 시각을 함께 기록한다(DB CHECK: 체결 ⇔ 주문·체결 시각 있음).
     * 호출하는 트랜잭션(주문 저장)에 참여한다. 벌크 UPDATE라 updated_at을 직접 넣는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExchangeReservation r
               set r.status = com.travelpay.exchange.domain.reservation.ReservationStatus.EXECUTED,
                   r.orderId = :orderId,
                   r.executedAt = :now,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = com.travelpay.exchange.domain.reservation.ReservationStatus.WAITING
            """)
    int markExecuted(@Param("id") UUID id, @Param("orderId") UUID orderId, @Param("now") Instant now);
}
