package com.travelpay.exchange.application.order;

import java.util.Objects;

import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import com.travelpay.exchange.domain.order.ManualReviewReason;

/**
 * 관리자 처리 이력 목록 항목입니다.
 * 관리자 처리 이력 한 건에, 처리 전에 사람 확인으로 넘어간 이유와 주문의 실패 사유를 붙입니다.
 *
 * @param history       관리자(MASTER) 상태 이력
 * @param reviewReason  사람 확인으로 넘어간 이유. 이력이 없거나 모르는 값이면 null
 * @param failureReason 주문 실패 사유. 실패로 처리하지 않았으면 null
 */
public record ExchangeResolutionView(
        ExchangeOrderStatusHistory history,
        ManualReviewReason reviewReason,
        ExchangeFailureReason failureReason
) {

    public ExchangeResolutionView {
        Objects.requireNonNull(history, "history");
    }
}
