package com.travelpay.exchange.application.order;

import java.util.Objects;

import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ManualReviewReason;

/**
 * 관리자 MANUAL_REVIEW 목록 조회 항목입니다.
 * 주문 정보와 해당 주문이 MANUAL_REVIEW로 빠진 사유를 함께 담습니다.
 */
public record ExchangeManualReviewView(
        ExchangeOrder order,
        ManualReviewReason reviewReason
) {

    public ExchangeManualReviewView {
        Objects.requireNonNull(order, "order");
    }
}
