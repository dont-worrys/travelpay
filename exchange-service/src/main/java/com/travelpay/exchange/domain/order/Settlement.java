package com.travelpay.exchange.domain.order;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerPostResult;

/**
 * PROCESSING 주문을 바꿀 값입니다. 원장 결과를 어떤 상태와 값으로 바꿀지는 여기서 정하고,
 * 저장소는 {@code WHERE status = 'PROCESSING'} 조건부 UPDATE로 이 값을 한 번만 반영합니다.
 * 같은 주문을 여러 흐름이 동시에 반영해도 상태 전이 규칙은 이 클래스 한 곳에만 있습니다.
 *
 * @param ledgerTransactionId COMPLETED일 때만 값이 있습니다.
 * @param failureReason       FAILED일 때만 값이 있습니다.
 * @param settledAt           COMPLETED·FAILED일 때만 값이 있습니다. MANUAL_REVIEW는 확정이 아닙니다.
 * @param reason              상태 이력에 남길 사유. COMPLETED는 null입니다.
 */
public record Settlement(ExchangeOrderStatus status, UUID ledgerTransactionId,
                         ExchangeFailureReason failureReason, Instant settledAt, String reason) {

    public Settlement {
        Objects.requireNonNull(status, "status");
        boolean valid = switch (status) {
            case COMPLETED -> ledgerTransactionId != null && failureReason == null && settledAt != null;
            case FAILED -> ledgerTransactionId == null && failureReason != null && settledAt != null;
            case MANUAL_REVIEW -> ledgerTransactionId == null && failureReason == null && settledAt == null
                    && reason != null;
            case PROCESSING -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid settlement for " + status);
        }
    }

    public static Settlement completed(UUID ledgerTransactionId, Instant settledAt) {
        return new Settlement(ExchangeOrderStatus.COMPLETED, ledgerTransactionId, null, settledAt, null);
    }

    public static Settlement failed(ExchangeFailureReason failureReason, Instant settledAt) {
        return new Settlement(ExchangeOrderStatus.FAILED, null, failureReason, settledAt, failureReason.name());
    }

    public static Settlement manualReview(ManualReviewReason reason) {
        return new Settlement(ExchangeOrderStatus.MANUAL_REVIEW, null, null, null, reason.name());
    }

    /**
     * 원장 결과를 주문 값으로 바꿉니다. 결과를 모르면(타임아웃·5xx) 바꿀 값이 없어 empty입니다.
     * 그 주문은 PROCESSING으로 남아 같은 요청을 다시 보냅니다.
     *
     * @param now 확정 시각. 서비스의 Clock 값입니다.
     */
    public static Optional<Settlement> from(LedgerPostResult result, Instant now) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(now, "now");
        return switch (result) {
            case LedgerPostResult.Posted posted -> Optional.of(completed(posted.transactionId(), now));
            case LedgerPostResult.Rejected rejected -> Optional.of(
                    ExchangeFailureReason.fromLedgerRejectCode(rejected.rejectCode())
                            .map(reason -> failed(reason, now))
                            .orElseGet(() -> manualReview(ManualReviewReason.LEDGER_UNKNOWN_REJECTION)));
            case LedgerPostResult.Unknown unknown -> Optional.empty();
            case LedgerPostResult.Invalid invalid -> Optional.of(manualReview(switch (invalid.kind()) {
                case BAD_REQUEST -> ManualReviewReason.LEDGER_BAD_REQUEST;
                case CONFLICT -> ManualReviewReason.LEDGER_CONFLICT;
                case MALFORMED_RESPONSE -> ManualReviewReason.LEDGER_MALFORMED_RESPONSE;
            }));
        };
    }

    /** 이 값으로 바뀐 기록입니다. 조건부 UPDATE가 1행을 바꿨을 때만 만듭니다(그때 이전 상태는 항상 PROCESSING). */
    public ExchangeOrderStatusHistory history(UUID orderId) {
        return ExchangeOrderStatusHistory.of(orderId, ExchangeOrderStatus.PROCESSING, status, reason,
                ChangedByType.SYSTEM);
    }
}
