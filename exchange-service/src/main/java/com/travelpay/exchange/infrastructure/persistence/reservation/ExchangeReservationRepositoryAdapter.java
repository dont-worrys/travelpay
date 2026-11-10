package com.travelpay.exchange.infrastructure.persistence.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.application.reservation.ExchangeReservationQueryRepository;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.reservation.DuplicateExchangeReservationException;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import com.travelpay.exchange.infrastructure.persistence.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** 도메인 예약 저장소와 예약 목록 조회 포트를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeReservationRepositoryAdapter implements ExchangeReservationRepository, ExchangeReservationQueryRepository {

    /** V7__create_exchange_reservations.sql: 같은 회원·멱등 키는 예약 하나 */
    static final String IDEMPOTENCY_UNIQUE = "uk_exchange_reservations_idempotency";

    private final ExchangeReservationJpaRepository jpaRepository;

    /**
     * 유니크 위반을 이 자리에서 구분하도록 커밋까지 미루지 않고 바로 INSERT합니다(주문 저장과 같은 방식).
     * 멱등 키 UNIQUE 위반만 도메인 예외로 바꾸고, CHECK 같은 다른 위반은 그대로 던집니다.
     */
    @Override
    public ExchangeReservation save(ExchangeReservation reservation) {
        try {
            return jpaRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, IDEMPOTENCY_UNIQUE)) {
                throw new DuplicateExchangeReservationException("이미 같은 멱등 키의 예약이 있습니다: userId="
                        + reservation.getUserId(), e);
            }
            throw e;
        }
    }

    @Override
    public Optional<ExchangeReservation> findByIdAndUserId(UUID reservationId, UUID userId) {
        return jpaRepository.findByIdAndUserId(reservationId, userId);
    }

    @Override
    public Optional<ExchangeReservation> findByUserIdAndIdempotencyKey(UUID userId, IdempotencyKey idempotencyKey) {
        return jpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey.value());
    }

    @Override
    public long countWaitingByUserId(UUID userId) {
        return jpaRepository.countWaitingByUserId(userId);
    }

    @Override
    public boolean cancelIfWaiting(UUID reservationId, UUID userId, Instant now) {
        return jpaRepository.cancelIfWaiting(reservationId, userId, now) == 1;
    }

    @Override
    public List<UUID> findTriggeredWaitingIds(Currency foreign, BigDecimal midRate, Instant now) {
        if (foreign == Currency.KRW) {
            throw new IllegalArgumentException("외화가 아닙니다: " + foreign);
        }
        return jpaRepository.findTriggeredWaitingIds(foreign.name(), midRate, now);
    }

    @Override
    public Page<ExchangeReservation> findPage(UUID userId, ReservationStatus status, Pageable pageable) {
        return jpaRepository.findPage(userId, status, pageable);
    }
}
