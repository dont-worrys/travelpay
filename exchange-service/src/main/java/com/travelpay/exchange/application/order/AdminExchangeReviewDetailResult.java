package com.travelpay.exchange.application.order;

import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.domain.order.ManualReviewResolution.Decision;
import com.travelpay.exchange.domain.order.ManualReviewResolutionReason;

/**
 * 관리자 MANUAL_REVIEW 단건 조회 결과입니다.
 * 주문 정보와 함께 원장 조회 결과 및 예상 처리 결과를 담습니다.
 */
public record AdminExchangeReviewDetailResult(
        ExchangeOrder order,
        ManualReviewReason reviewReason,
        boolean ledgerLookupFailed,
        LedgerSummary ledger,
        ExpectedSummary expected
) {

    public record LedgerSummary(
            String status,
            UUID transactionId,
            String rejectReason,
            List<LedgerLookupResult.Entry> entries
    ) {}

    public record ExpectedSummary(
            boolean resolvable,
            ExchangeOrderStatus toStatus,
            ExchangeFailureReason failureReason,
            ManualReviewResolutionReason reason,
            String description,
            List<LedgerLookupResult.Entry> expectedEntries,
            List<LedgerLookupResult.Entry> actualEntries
    ) {}

    public static AdminExchangeReviewDetailResult lookupFailed(ExchangeOrder order, ManualReviewReason reviewReason) {
        return new AdminExchangeReviewDetailResult(order, reviewReason, true, null, null);
    }

    public static AdminExchangeReviewDetailResult of(ExchangeOrder order,
                                                     ManualReviewReason reviewReason,
                                                     LedgerLookupResult lookup,
                                                     Decision decision) {
        LedgerSummary ledgerSummary = switch (lookup) {
            case LedgerLookupResult.Posted posted -> new LedgerSummary(
                    "POSTED", posted.transactionId(), null, posted.entries());
            case LedgerLookupResult.Rejected rejected -> new LedgerSummary(
                    "REJECTED", rejected.transactionId(), rejected.rejectReason(), List.of());
            case LedgerLookupResult.NotFound ignored -> new LedgerSummary(
                    "NOT_FOUND", null, null, List.of());
            case LedgerLookupResult.Unavailable ignored -> null;
        };

        ExpectedSummary expectedSummary = switch (decision) {
            case Decision.Resolvable resolvable -> {
                String desc = describeResolvable(reviewReason, lookup, resolvable);
                yield new ExpectedSummary(
                        true,
                        resolvable.toStatus(),
                        resolvable.failureReason(),
                        resolvable.reason(),
                        desc,
                        null,
                        null
                );
            }
            case Decision.EntryMismatch mismatch -> new ExpectedSummary(
                    false,
                    null,
                    null,
                    null,
                    cause(reviewReason) + ", 원장 반영 확인(금액·계정 불일치) → 원장 보정 필요(처리 불가)",
                    mismatch.expectedEntries(),
                    mismatch.actualEntries()
            );
        };

        return new AdminExchangeReviewDetailResult(order, reviewReason, false, ledgerSummary, expectedSummary);
    }

    private static String describeResolvable(ManualReviewReason reviewReason,
                                             LedgerLookupResult lookup,
                                             Decision.Resolvable resolvable) {
        String outcome = resolvable.toStatus() == ExchangeOrderStatus.COMPLETED
                ? "COMPLETED"
                : "FAILED(" + resolvable.failureReason() + ")";
        String ledgerResult = switch (lookup) {
            case LedgerLookupResult.Posted ignored -> "원장 반영 확인";
            case LedgerLookupResult.Rejected ignored -> "원장 거절 확인";
            case LedgerLookupResult.NotFound ignored -> "원장 기록 없음";
            case LedgerLookupResult.Unavailable ignored -> "원장 조회 실패";
        };
        return cause(reviewReason) + ", " + ledgerResult + " → " + outcome;
    }

    private static String cause(ManualReviewReason reason) {
        if (reason == null) {
            return "사유 없음";
        }
        return switch (reason) {
            case LEDGER_BAD_REQUEST -> "원장 400(형식 오류)";
            case LEDGER_CONFLICT -> "원장 409(거래 충돌)";
            case LEDGER_MALFORMED_RESPONSE -> "원장 응답 이상";
            case LEDGER_UNKNOWN_REJECTION -> "원장 알 수 없는 거절";
            case LEDGER_UNCONFIRMED_TOO_LONG -> "신청 후 24시간 초과";
        };
    }
}
