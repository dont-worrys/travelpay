package com.travelpay.exchange.infrastructure.persistence.reservation;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 예약 저장소를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeReservationRepositoryAdapter implements ExchangeReservationRepository {

    private final ExchangeReservationJpaRepository jpaRepository;

    @Override
    public ExchangeReservation save(ExchangeReservation reservation) {
        return jpaRepository.save(reservation);
    }

    @Override
    public Optional<ExchangeReservation> findByIdAndUserId(UUID reservationId, UUID userId) {
        return jpaRepository.findByIdAndUserId(reservationId, userId);
    }
}
