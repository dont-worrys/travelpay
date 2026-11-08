package com.travelpay.exchange.domain.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerLookupResult;

/**
 * MANUAL_REVIEW 주문을 원장 단건 조회 결과로 정리하는 도메인 규칙과 확정 값입니다.
 * 단건 조회(예상 처리 결과)와 처리 실행 API가 모두 {@link #decide}를 사용해 같은 기준으로 판단합니다.
 *
 * <ul>
 *   <li>POSTED + 항목 일치: MANUAL_REVIEW 사유와 관계없이 COMPLETED (원장 거래 ID 저장)</li>
 *   <li>POSTED + 항목 불일치: 처리 불가(409 원장 보정 필요, MANUAL_REVIEW 유지)</li>
 *   <li>REJECTED: FAILED (아는 거절 사유 3종이면 그대로, 모르는 사유이거나 LEDGER_CONFLICT 주문이면 PROCESSING_ERROR)</li>
 *   <li>기록 없음(NotFound): 재시도하지 않고 FAILED(NOT_PROCESSED)로 취소</li>
 * </ul>
 *
 * @param status                    바뀔 주문 상태 (COMPLETED 또는 FAILED)
 * @param orderLedgerTransactionId  주문 테이블(exchange_orders.ledger_transaction_id)에 저장할 원장 거래 ID (COMPLETED일 때만 값 있음)
 * @param historyLedgerTransactionId 상태 이력(exchange_order_status_histories.ledger_transaction_id)에 남길 근거 원장 거래 ID (POSTED·REJECTED면 값 있음, 기록 없음이면 null)
 * @param failureReason             실패 사유 (FAILED일 때만 값 있음)
 * @param reason                    상태 이력에 남길 판단 근거 (LEDGER_POSTED / LEDGER_REJECTED / LEDGER_NOT_FOUND)
 * @param settledAt                 확정 시각
 * @param actorId                   처리한 관리자(MASTER) ID
 * @param memo                      관리자 비고 (선택, 255자 이하)
 */
public record ManualReviewResolution(
        ExchangeOrderStatus status,
        UUID orderLedgerTransactionId,
        UUID historyLedgerTransactionId,
        ExchangeFailureReason failureReason,
        ManualReviewResolutionReason reason,
        Instant settledAt,
        UUID actorId,
        String memo
) {

    private static final int MAX_MEMO_LENGTH = 255;

    public ManualReviewResolution {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(settledAt, "settledAt");
        Objects.requireNonNull(actorId, "actorId");
        memo = normalizeMemo(memo);
        boolean valid = switch (status) {
            case COMPLETED -> orderLedgerTransactionId != null
                    && orderLedgerTransactionId.equals(historyLedgerTransactionId)
                    && failureReason == null
                    && reason == ManualReviewResolutionReason.LEDGER_POSTED;
            case FAILED -> orderLedgerTransactionId == null
                    && failureReason != null
                    && ((reason == ManualReviewResolutionReason.LEDGER_REJECTED && historyLedgerTransactionId != null)
                    || (reason == ManualReviewResolutionReason.LEDGER_NOT_FOUND && historyLedgerTransactionId == null));
            case PROCESSING, MANUAL_REVIEW -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid manual review resolution for " + status + " / " + reason);
        }
    }

    /**
     * 주문의 기대 항목, MANUAL_REVIEW 진입 사유, 원장 조회 결과로 예상 처리 결과를 계산합니다.
     * 원장 조회 실패({@link LedgerLookupResult.Unavailable})는 판단할 수 없으므로 호출하는 쪽이 먼저 처리합니다.
     */
    public static Decision decide(ExchangeOrder order, ManualReviewReason reviewReason, LedgerLookupResult lookup) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(reviewReason, "reviewReason");
        Objects.requireNonNull(lookup, "lookup");
        return switch (lookup) {
            case LedgerLookupResult.Posted posted -> decidePosted(order.expectedLedgerEntries(), posted);
            case LedgerLookupResult.Rejected rejected -> decideRejected(reviewReason, rejected);
            case LedgerLookupResult.NotFound ignored -> Decision.NotFound.INSTANCE;
            case LedgerLookupResult.Unavailable unavailable ->
                    throw new IllegalArgumentException("cannot decide when ledger lookup is unavailable: "
                            + unavailable.cause());
        };
    }

    private static Decision decidePosted(List<LedgerLookupResult.Entry> expectedEntries,
                                         LedgerLookupResult.Posted posted) {
        if (!sameEntries(expectedEntries, posted.entries())) {
            return new Decision.EntryMismatch(posted.transactionId(), expectedEntries, posted.entries());
        }
        return new Decision.Resolvable(
                ExchangeOrderStatus.COMPLETED,
                null,
                ManualReviewResolutionReason.LEDGER_POSTED,
                posted.transactionId()
        );
    }

    private static Decision decideRejected(ManualReviewReason reviewReason, LedgerLookupResult.Rejected rejected) {
        // LEDGER_CONFLICT 주문은 원장의 거절 기록이 다른 요청의 것일 수 있으므로 일반 사유(PROCESSING_ERROR)로 둔다
        ExchangeFailureReason failureReason = reviewReason == ManualReviewReason.LEDGER_CONFLICT
                ? ExchangeFailureReason.PROCESSING_ERROR
                : ExchangeFailureReason.fromLedgerRejectReason(rejected.rejectReason())
                        .orElse(ExchangeFailureReason.PROCESSING_ERROR);
        return new Decision.Resolvable(
                ExchangeOrderStatus.FAILED,
                failureReason,
                ManualReviewResolutionReason.LEDGER_REJECTED,
                rejected.transactionId()
        );
    }

    /** 기대 항목과 원장 기록 항목을 순서와 관계없이(개수 포함) 비교합니다. */
    public static boolean sameEntries(List<LedgerLookupResult.Entry> expected, List<LedgerLookupResult.Entry> actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        if (expected.size() != actual.size()) {
            return false;
        }
        List<LedgerLookupResult.Entry> remaining = new ArrayList<>(actual);
        for (LedgerLookupResult.Entry entry : expected) {
            if (!remaining.remove(entry)) {
                return false;
            }
        }
        return true;
    }

    /** 조건부 UPDATE가 MANUAL_REVIEW 주문을 바꿨을 때 같은 트랜잭션에 저장할 관리자 상태 이력입니다. */
    public ExchangeOrderStatusHistory history(UUID orderId) {
        return ExchangeOrderStatusHistory.byMaster(
                orderId,
                ExchangeOrderStatus.MANUAL_REVIEW,
                status,
                reason.name(),
                actorId,
                historyLedgerTransactionId,
                memo
        );
    }

    private static String normalizeMemo(String memo) {
        if (memo == null) {
            return null;
        }
        String trimmed = memo.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_MEMO_LENGTH) {
            throw new IllegalArgumentException("memo must be at most " + MAX_MEMO_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * 원장 조회 결과를 바탕으로 계산한 판단 결과입니다.
     * {@link Resolvable}이면 처리 가능하고, {@link EntryMismatch}면 원장 보정이 필요해 처리할 수 없습니다.
     */
    public sealed interface Decision {

        boolean resolvable();

        /** 원장에 기록이 없어 FAILED(NOT_PROCESSED)로 취소하는 결정입니다. */
        final class NotFound {
            private static final Resolvable INSTANCE = new Resolvable(
                    ExchangeOrderStatus.FAILED,
                    ExchangeFailureReason.NOT_PROCESSED,
                    ManualReviewResolutionReason.LEDGER_NOT_FOUND,
                    null
            );

            private NotFound() {
            }
        }

        /**
         * 주문을 COMPLETED 또는 FAILED로 확정할 수 있는 경우입니다.
         *
         * @param toStatus            바뀔 주문 상태 (COMPLETED 또는 FAILED)
         * @param failureReason       FAILED일 때의 실패 사유 (COMPLETED면 null)
         * @param reason              상태 이력에 남길 판단 근거
         * @param ledgerTransactionId 근거 원장 거래 ID (POSTED·REJECTED면 값 있음, LEDGER_NOT_FOUND면 null)
         */
        record Resolvable(
                ExchangeOrderStatus toStatus,
                ExchangeFailureReason failureReason,
                ManualReviewResolutionReason reason,
                UUID ledgerTransactionId
        ) implements Decision {

            public Resolvable {
                Objects.requireNonNull(toStatus, "toStatus");
                Objects.requireNonNull(reason, "reason");
            }

            @Override
            public boolean resolvable() {
                return true;
            }

            /** 관리자 ID·메모·확정 시각을 붙여 실제 DB 반영용 {@link ManualReviewResolution}을 만듭니다. */
            public ManualReviewResolution toResolution(UUID actorId, String memo, Instant settledAt) {
                UUID orderLedgerTxId = toStatus == ExchangeOrderStatus.COMPLETED ? ledgerTransactionId : null;
                return new ManualReviewResolution(
                        toStatus,
                        orderLedgerTxId,
                        ledgerTransactionId,
                        failureReason,
                        reason,
                        settledAt,
                        actorId,
                        memo
                );
            }
        }

        /**
         * 원장에 POSTED되어 있으나 기록된 항목이 우리 주문의 기대 항목과 다른 경우입니다.
         * 거절로 끝내지 않고 MANUAL_REVIEW를 유지하며, 단건 조회에서 기대 항목과 원장 항목을 함께 보여 줍니다.
         */
        record EntryMismatch(
                UUID ledgerTransactionId,
                List<LedgerLookupResult.Entry> expectedEntries,
                List<LedgerLookupResult.Entry> actualEntries
        ) implements Decision {

            public EntryMismatch {
                Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId");
                expectedEntries = List.copyOf(expectedEntries);
                actualEntries = List.copyOf(actualEntries);
            }

            @Override
            public boolean resolvable() {
                return false;
            }
        }
    }
}
