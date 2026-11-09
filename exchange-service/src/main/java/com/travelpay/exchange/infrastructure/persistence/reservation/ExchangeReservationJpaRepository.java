package com.travelpay.exchange.infrastructure.persistence.reservation;

import java.time.Instant;
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
}
