package com.travelpay.exchange.infrastructure.persistence.reservation;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExchangeReservationJpaRepository extends JpaRepository<ExchangeReservation, UUID> {

    Optional<ExchangeReservation> findByIdAndUserId(UUID id, UUID userId);
}
