package com.travelpay.exchange.domain.reservation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
}
