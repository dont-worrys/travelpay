package com.travelpay.exchange.infrastructure.persistence.order;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.travelpay.exchange.application.order.ExchangeManualReviewView;
import com.travelpay.exchange.application.order.ExchangeOrderListCondition;
import com.travelpay.exchange.application.order.ExchangeOrderQueryRepository;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderType;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.infrastructure.persistence.order.ExchangeOrderStatusHistoryJpaRepository.ManualReviewReasonProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** 환전 주문 목록 조회 포트를 Spring Data JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
class ExchangeOrderQueryRepositoryAdapter implements ExchangeOrderQueryRepository {

    private final ExchangeOrderJpaRepository jpaRepository;
    private final ExchangeOrderStatusHistoryJpaRepository historyJpaRepository;

    /** 환전은 보내는 통화가, 재환전은 받는 통화가 원화인 주문으로 거릅니다. */
    @Override
    public Page<ExchangeOrder> findPage(ExchangeOrderListCondition condition, Pageable pageable) {
        ExchangeOrderType type = condition.type();
        Currency fromCurrency = type == ExchangeOrderType.EXCHANGE ? Currency.KRW : null;
        Currency toCurrency = type == ExchangeOrderType.RE_EXCHANGE ? Currency.KRW : null;
        return jpaRepository.findPage(condition.userId(), condition.status(), fromCurrency, toCurrency, pageable);
    }

    @Override
    public Page<ExchangeManualReviewView> findManualReviews(ManualReviewReason reviewReason, Pageable pageable) {
        String reasonName = reviewReason != null ? reviewReason.name() : null;
        Page<ExchangeOrder> orders = jpaRepository.findManualReviews(reasonName, pageable);
        if (orders.isEmpty()) {
            return Page.empty(pageable);
        }
        List<UUID> orderIds = orders.map(ExchangeOrder::getId).toList();
        Map<UUID, ManualReviewReason> reasonsByOrderId = historyJpaRepository
                .findManualReviewReasonsByOrderIds(orderIds).stream()
                .collect(Collectors.toMap(
                        ManualReviewReasonProjection::getOrderId,
                        p -> parseReason(p.getReason()),
                        (first, second) -> first
                ));
        return orders.map(order -> new ExchangeManualReviewView(order, reasonsByOrderId.get(order.getId())));
    }

    private static ManualReviewReason parseReason(String reason) {
        if (reason == null) {
            return null;
        }
        try {
            return ManualReviewReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
