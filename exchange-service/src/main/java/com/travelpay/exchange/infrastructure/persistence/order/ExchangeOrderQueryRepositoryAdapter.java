package com.travelpay.exchange.infrastructure.persistence.order;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.exchange.application.order.ExchangeManualReviewView;
import com.travelpay.exchange.application.order.ExchangeOrderListCondition;
import com.travelpay.exchange.application.order.ExchangeOrderQueryRepository;
import com.travelpay.exchange.application.order.ExchangeResolutionCondition;
import com.travelpay.exchange.application.order.ExchangeResolutionView;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
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
        Map<UUID, ManualReviewReason> reasonsByOrderId = latestManualReviewReasons(orders.map(ExchangeOrder::getId).toList());
        return orders.map(order -> new ExchangeManualReviewView(order, reasonsByOrderId.get(order.getId())));
    }

    /**
     * 관리자 처리 이력 한 페이지를 읽고, 그 페이지 주문들의 사람 확인 사유와 실패 사유를 쿼리 한 번씩으로 붙입니다.
     * 행마다 따로 조회하면 페이지 크기만큼 쿼리가 늘어나므로(N+1) 주문 ID를 모아 한 번에 읽습니다.
     */
    @Override
    public Page<ExchangeResolutionView> findResolutions(ExchangeResolutionCondition condition, Pageable pageable) {
        Page<ExchangeOrderStatusHistory> histories = historyJpaRepository.findResolutions(
                condition.actorId(), condition.orderId(), condition.from(), condition.to(), pageable);
        if (histories.isEmpty()) {
            return Page.empty(pageable);
        }
        List<UUID> orderIds = histories.stream().map(ExchangeOrderStatusHistory::getOrderId).distinct().toList();
        Map<UUID, ManualReviewReason> reviewReasons = latestManualReviewReasons(orderIds);
        // 실패 사유는 null일 수 있어(COMPLETED로 처리) Collectors.toMap 대신 반복문으로 담습니다.
        Map<UUID, ExchangeFailureReason> failureReasons = new HashMap<>();
        for (ExchangeOrder order : jpaRepository.findAllById(orderIds)) {
            failureReasons.put(order.getId(), order.getFailureReason());
        }
        return histories.map(history -> new ExchangeResolutionView(history,
                reviewReasons.get(history.getOrderId()), failureReasons.get(history.getOrderId())));
    }

    /**
     * 주문마다 가장 최근에 사람 확인으로 넘어간 사유입니다. 행은 최신순이라 주문마다 처음 나온 사유를 씁니다.
     * 사유가 비었거나 모르는 값이면 null로 두고 목록은 그대로 보여 줍니다.
     * Collectors.toMap은 값이 null이면 예외를 던져 한 행 때문에 목록 전체가 실패하므로, 반복문으로 담습니다.
     */
    private Map<UUID, ManualReviewReason> latestManualReviewReasons(List<UUID> orderIds) {
        Map<UUID, ManualReviewReason> reasonsByOrderId = new HashMap<>();
        for (ManualReviewReasonProjection row : historyJpaRepository.findManualReviewReasonsByOrderIds(orderIds)) {
            if (!reasonsByOrderId.containsKey(row.getOrderId())) {
                reasonsByOrderId.put(row.getOrderId(), parseReason(row.getReason()));
            }
        }
        return reasonsByOrderId;
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
