package com.travelpay.exchange.infrastructure.persistence.order;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.order.DuplicateExchangeOrderException;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.infrastructure.persistence.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** 도메인 환전 주문 저장소를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeOrderRepositoryAdapter implements ExchangeOrderRepository {

    /** V3__create_exchange_orders.sql: 같은 견적은 한 번만 주문에 사용 */
    static final String QUOTE_UNIQUE = "uk_exchange_orders_quote";
    /** V3__create_exchange_orders.sql: 같은 회원·멱등 키는 주문 하나 */
    static final String IDEMPOTENCY_UNIQUE = "uk_exchange_orders_idempotency";

    private final ExchangeOrderJpaRepository jpaRepository;

    /**
     * 유니크 위반을 이 자리에서 구분하도록 커밋까지 미루지 않고 바로 INSERT합니다.
     * 주문 UNIQUE 두 개의 위반만 도메인 예외로 바꾸고, CHECK 같은 다른 위반은 그대로 던집니다.
     * 어느 UNIQUE에 걸렸는지는 신청 쪽이 새 트랜잭션에서 다시 조회해 가립니다.
     * PostgreSQL은 제약 위반 뒤 같은 트랜잭션에서 더 조회할 수 없기 때문입니다.
     */
    @Override
    public ExchangeOrder save(ExchangeOrder order) {
        try {
            return jpaRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, QUOTE_UNIQUE, IDEMPOTENCY_UNIQUE)) {
                throw new DuplicateExchangeOrderException("이미 같은 견적 또는 멱등 키의 주문이 있습니다: quoteId="
                        + order.getQuoteId() + ", userId=" + order.getUserId(), e);
            }
            throw e;
        }
    }

    @Override
    public Optional<ExchangeOrder> findByUserIdAndIdempotencyKey(UUID userId, IdempotencyKey idempotencyKey) {
        return jpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey.value());
    }

    @Override
    public Optional<ExchangeOrder> findByIdAndUserId(UUID orderId, UUID userId) {
        return jpaRepository.findByIdAndUserId(orderId, userId);
    }
}
