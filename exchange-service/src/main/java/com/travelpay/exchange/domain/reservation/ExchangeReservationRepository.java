package com.travelpay.exchange.domain.reservation;

import java.util.Optional;
import java.util.UUID;

/** 예약 환전 저장소입니다. 구현은 infrastructure 계층이 담당합니다. 상태는 조건부 UPDATE로만 바꿉니다(다음 단계에서 추가). */
public interface ExchangeReservationRepository {

    ExchangeReservation save(ExchangeReservation reservation);

    /** 요청자 본인의 예약만 찾습니다. 다른 회원의 예약은 없는 것과 같게 다룹니다. */
    Optional<ExchangeReservation> findByIdAndUserId(UUID reservationId, UUID userId);
}
