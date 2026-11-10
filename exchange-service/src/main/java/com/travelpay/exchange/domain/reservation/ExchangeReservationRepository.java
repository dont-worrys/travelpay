package com.travelpay.exchange.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.IdempotencyKey;

/**
 * 예약 환전 저장소입니다. 구현은 infrastructure 계층이 담당합니다.
 * 상태는 조건부 UPDATE(WHERE status = 'WAITING')로만 바꿉니다. 취소·체결·만료가 겹쳐도 한쪽만 반영되게 하려는 것입니다.
 * 페이지 목록은 Spring 타입(Page)을 쓰므로 애플리케이션 계층의 조회 포트(ExchangeReservationQueryRepository)에 둡니다.
 */
public interface ExchangeReservationRepository {

    /**
     * 예약을 바로 저장합니다(커밋까지 미루지 않음).
     *
     * @throws DuplicateExchangeReservationException 같은 회원·같은 멱등 키의 예약이 이미 있음
     */
    ExchangeReservation save(ExchangeReservation reservation);

    /** 요청자 본인의 예약만 찾습니다. 다른 회원의 예약은 없는 것과 같게 다룹니다. */
    Optional<ExchangeReservation> findByIdAndUserId(UUID reservationId, UUID userId);

    /** 같은 회원이 같은 멱등 키로 낸 예약입니다. 재요청인지 판단할 때 씁니다. */
    Optional<ExchangeReservation> findByUserIdAndIdempotencyKey(UUID userId, IdempotencyKey idempotencyKey);

    /** 회원이 지금 걸어 둔 대기(WAITING) 예약 수입니다. 회원당 대기 예약 한도를 확인할 때 씁니다. */
    long countWaitingByUserId(UUID userId);

    /**
     * 본인의 대기 예약을 취소합니다. 대기 중일 때만 바뀌고 true입니다.
     * 이미 체결·취소·만료됐거나, 없거나, 다른 회원의 예약이면 바꾸지 않고 false입니다.
     * 체결과 동시에 일어나도 둘 중 하나만 반영됩니다(같은 행을 WHERE status = 'WAITING'으로 바꾸므로).
     */
    boolean cancelIfWaiting(UUID reservationId, UUID userId, Instant now);

    /**
     * 외화 환율이 midRate일 때 체결 조건에 닿은 대기 예약 ID입니다. 먼저 예약한 순서로 돌려줍니다.
     * 원화로 그 외화를 사는 예약은 목표 환율이 midRate 이상일 때, 그 외화를 원화로 파는 예약은 이하일 때입니다.
     * 만료 시각이 now 이전인 대기 예약은 만료 처리 대상이라 뺍니다.
     * ID만 돌려주는 것은 체결할 때 행을 잠그고 다시 읽기 때문입니다(그사이 취소됐을 수 있음).
     */
    List<UUID> findTriggeredWaitingIds(Currency foreign, BigDecimal midRate, Instant now);

    /** 회원 확인 없이 예약을 찾습니다. 체결처럼 시스템이 처리할 때만 씁니다. */
    Optional<ExchangeReservation> findById(UUID reservationId);

    /**
     * 대기 예약을 잠그고 읽습니다(SELECT ... FOR UPDATE SKIP LOCKED). 트랜잭션 안에서 불러야 합니다.
     * 다른 트랜잭션이 이미 잠근 행(다른 인스턴스의 체결, 사용자 취소 진행 중)이면 기다리지 않고 empty입니다.
     * 대기 중이 아니어도 empty입니다.
     */
    Optional<ExchangeReservation> lockWaiting(UUID reservationId);

    /**
     * 대기 예약을 체결로 바꾸고 주문 ID·체결 시각을 함께 기록합니다. 대기 중일 때만 바뀌고 true입니다.
     * 주문 저장과 같은 트랜잭션에서 불러, 주문은 있는데 예약이 대기로 남거나 그 반대가 되는 일이 없게 합니다.
     */
    boolean markExecuted(UUID reservationId, UUID orderId, Instant now);
}
