package com.travelpay.exchange.infrastructure.external.ledger;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 원장 거래 등록·단건 조회 응답의 data입니다. 바깥 형식 {success, data, error}는 공통 ApiResponse로 받습니다.
 * 등록 응답은 transactionId·status만 오고, 단건 조회 응답은 rejectReason·referenceType·referenceId·entries까지 옵니다.
 * Feign이 Jackson으로 만들므로 public이어야 합니다.
 *
 * @param status POSTED 또는 REJECTED. 등록 시 거절은 422 오류로 오므로 등록 성공 응답은 POSTED여야 합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerTransactionResponse(
        UUID transactionId,
        String status,
        String rejectReason,
        String referenceType,
        UUID referenceId,
        List<Entry> entries
) {

    public LedgerTransactionResponse(UUID transactionId, String status) {
        this(transactionId, status, null, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String ownerType,
            UUID walletId,
            String accountType,
            String currency,
            String direction,
            Long amount,
            String entryType
    ) {
    }
}
