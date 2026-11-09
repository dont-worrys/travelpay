package com.travelpay.exchange.presentation.order;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.application.order.ExchangeResolutionView;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;

/**
 * 관리자 처리 이력 목록 항목입니다.
 *
 * - resolutionReason: 처리 근거 (LEDGER_POSTED / LEDGER_REJECTED / LEDGER_NOT_FOUND)
 * - reviewReason: 처리 전에 사람 확인으로 넘어간 이유
 * - failureReason: 실패로 처리했을 때의 주문 실패 사유
 * - ledgerTransactionId: 처리 근거가 된 원장 거래 ID (기록 없음이면 null)
 */
public record AdminExchangeResolutionResponse(
        UUID historyId,
        UUID orderId,
        UUID actorId,
        String fromStatus,
        String toStatus,
        String resolutionReason,
        String reviewReason,
        String failureReason,
        UUID ledgerTransactionId,
        String memo,
        Instant resolvedAt
) {
    public static AdminExchangeResolutionResponse from(ExchangeResolutionView view) {
        ExchangeOrderStatusHistory history = view.history();
        return new AdminExchangeResolutionResponse(
                history.getId(),
                history.getOrderId(),
                history.getChangedById(),
                history.getFromStatus() != null ? history.getFromStatus().name() : null,
                history.getToStatus().name(),
                history.getReason(),
                view.reviewReason() != null ? view.reviewReason().name() : null,
                view.failureReason() != null ? view.failureReason().name() : null,
                history.getLedgerTransactionId(),
                history.getMemo(),
                history.getCreatedAt()
        );
    }
}
