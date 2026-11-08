package com.travelpay.payment.application.payment.dto;

import com.travelpay.payment.domain.payment.PaymentActor;
import com.travelpay.payment.domain.payment.PaymentHistory;
import com.travelpay.payment.domain.payment.PaymentStatus;

import java.time.Instant;
import java.util.List;

public record RefundRequestSummary(Result result, Instant requestedAt, String requestReason,
                                   Instant processedAt, String rejectReason) {

    public enum Result {
        REQUESTED,  // 가맹점 처리 대기
        CANCELLED,  // 고객 취소
        REJECTED,   // 가맹점 주인·MASTER 거절
        ACCEPTED    // 가맹점이 환불 실행 (환불 결과는 환불 내역에서 확인)
    }

    /** 이력은 오래된 순이어야 합니다. 신청한 적이 없으면 null */
    public static RefundRequestSummary from(List<PaymentHistory> historiesOldestFirst) {
        int requestIndex = -1;
        for (int i = historiesOldestFirst.size() - 1; i >= 0; i--) {
            if (historiesOldestFirst.get(i).getCurrentStatus() == PaymentStatus.REFUND_REQUESTED) {
                requestIndex = i;
                break;
            }
        }
        if (requestIndex < 0) {
            return null;
        }
        PaymentHistory request = historiesOldestFirst.get(requestIndex);
        if (requestIndex == historiesOldestFirst.size() - 1) {
            return new RefundRequestSummary(Result.REQUESTED, request.getCreatedAt(), request.getReason(), null, null);
        }
        PaymentHistory next = historiesOldestFirst.get(requestIndex + 1);
        if (next.getCurrentStatus() == PaymentStatus.REFUND_PENDING) {
            return new RefundRequestSummary(Result.ACCEPTED, request.getCreatedAt(), request.getReason(),
                    next.getCreatedAt(), null);
        }
        if (next.getCreatedBy() == PaymentActor.USER) {
            return new RefundRequestSummary(Result.CANCELLED, request.getCreatedAt(), request.getReason(),
                    next.getCreatedAt(), null);
        }
        return new RefundRequestSummary(Result.REJECTED, request.getCreatedAt(), request.getReason(),
                next.getCreatedAt(), next.getReason());
    }
}
