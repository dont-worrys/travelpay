package com.travelpay.exchange.domain.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 원장 거래 단건 조회(GET /internal/v1/ledger/transactions) 결과입니다.
 * 원장 클라이언트는 HTTP 응답과 호출 실패를 예외 대신 이 네 가지로 돌려줍니다.
 */
public sealed interface LedgerLookupResult {

    /**
     * 원장에 거래가 반영(POSTED)되어 있습니다.
     * 기록된 항목을 주문의 기대 항목과 비교해 일치 여부를 판단합니다.
     */
    record Posted(UUID transactionId, List<Entry> entries) implements LedgerLookupResult {

        public Posted {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("posted ledger transaction must have entries");
            }
            entries = List.copyOf(entries);
        }
    }

    /**
     * 원장에서 거절(REJECTED)된 거래입니다. 자금 이동은 일어나지 않았습니다.
     *
     * @param rejectReason 원장 거절 사유(INSUFFICIENT_BALANCE 등). 없을 수도 있습니다.
     */
    record Rejected(UUID transactionId, String rejectReason) implements LedgerLookupResult {

        public Rejected {
            Objects.requireNonNull(transactionId, "transactionId");
        }
    }

    /**
     * 원장이 404 + LEDGER_TRANSACTION_NOT_FOUND로 응답해 거래 기록이 없음을 확인한 경우입니다.
     * 코드 없는 404(경로 오류 등)는 기록 없음으로 단정하지 않고 {@link Unavailable}로 분류합니다.
     */
    record NotFound() implements LedgerLookupResult {
    }

    /**
     * 원장 조회에 실패했거나 응답이 계약과 달라 결론을 내릴 수 없는 경우입니다.
     * (코드 없는 404, 그 밖의 4xx·5xx, 타임아웃·연결 실패, POSTED인데 항목 없음, 알 수 없는 상태 등)
     */
    record Unavailable(String cause) implements LedgerLookupResult {

        public Unavailable {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * 원장 단건 조회 응답의 항목 한 줄입니다.
     * 원장은 거래 등록 요청 때 USER 항목의 accountType을 비워 보내도 조회 응답에는 {@code "WALLET"}을 채워 주므로,
     * 우리 주문의 {@link LedgerEntry}를 변환할 때도 USER 항목의 accountType을 {@code "WALLET"}으로 맞춰 비교합니다.
     */
    record Entry(
            String ownerType,
            UUID walletId,
            String accountType,
            String currency,
            String direction,
            long amount,
            String entryType
    ) {

        public static final String USER_OWNER = "USER";
        public static final String SYSTEM_OWNER = "SYSTEM";
        public static final String WALLET_ACCOUNT_TYPE = "WALLET";

        public Entry {
            Objects.requireNonNull(ownerType, "ownerType");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(entryType, "entryType");
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
        }

        /** 주문으로 만든 원장 요청 항목을 원장 단건 조회 응답 형식(USER → accountType WALLET)으로 바꿉니다. */
        public static Entry from(LedgerEntry entry) {
            Objects.requireNonNull(entry, "entry");
            return new Entry(
                    entry.isWalletEntry() ? USER_OWNER : SYSTEM_OWNER,
                    entry.walletId(),
                    entry.isWalletEntry() ? WALLET_ACCOUNT_TYPE : entry.systemAccount().name(),
                    entry.money().currency().name(),
                    entry.direction().name(),
                    entry.money().minorUnits(),
                    entry.entryType().name()
            );
        }

        public static List<Entry> fromEntries(List<LedgerEntry> entries) {
            Objects.requireNonNull(entries, "entries");
            return entries.stream().map(Entry::from).toList();
        }
    }
}
