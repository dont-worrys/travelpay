package com.travelpay.exchange.application.reservation;

import java.util.UUID;

import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 예약 목록 조회 포트입니다. 구현은 infrastructure 계층이 담당합니다.
 * 페이지 조회는 Spring Data의 Page·Pageable을 쓰는데, 도메인은 Spring에 의존하지 않는다는 규칙(ArchitectureTest)이 있어
 * 주문 목록(ExchangeOrderQueryRepository)과 같이 애플리케이션 계층의 조회 전용 포트로 둡니다.
 */
public interface ExchangeReservationQueryRepository {

    /**
     * 회원의 예약을 pageable의 페이지·크기·정렬대로 돌려줍니다. 정렬 검증과 크기 제한은 호출하는 쪽이 합니다.
     *
     * @param status 상태 필터. null이면 모든 상태
     */
    Page<ExchangeReservation> findPage(UUID userId, ReservationStatus status, Pageable pageable);
}
