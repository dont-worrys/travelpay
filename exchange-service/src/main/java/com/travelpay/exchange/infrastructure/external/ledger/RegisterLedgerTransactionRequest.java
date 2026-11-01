package com.travelpay.exchange.infrastructure.external.ledger;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.travelpay.exchange.domain.ledger.LedgerEntry;
import com.travelpay.exchange.domain.ledger.LedgerPosting;

/**
 * 원장 거래 등록 요청입니다. 금액은 최소 단위 정수이고, 비어 있는 값(null)은 보내지 않습니다.
 * 사용자 계정은 지갑 ID + 통화, 회사 계정은 계정 유형 + 통화로 지정합니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterLedgerTransactionRequest(String referenceType, UUID referenceId, UUID userId,
                                               List<Entry> entries) {

    static final String REFERENCE_TYPE = "EXCHANGE";

    public static RegisterLedgerTransactionRequest from(LedgerPosting posting) {
        return new RegisterLedgerTransactionRequest(REFERENCE_TYPE, posting.orderId(), posting.userId(),
                posting.entries().stream().map(Entry::from).toList());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String ownerType, UUID walletId, String accountType, String currency, String direction,
                        long amount, String entryType) {

        static Entry from(LedgerEntry entry) {
            return new Entry(
                    entry.isWalletEntry() ? "USER" : "SYSTEM",
                    entry.walletId(),
                    entry.isWalletEntry() ? null : entry.systemAccount().name(),
                    entry.money().currency().name(),
                    entry.direction().name(),
                    entry.money().minorUnits(),
                    entry.entryType().name());
        }
    }
}
