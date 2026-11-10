package com.travelpay.wallet.infra.client.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * 원장 거래 조회 응답 (대사용). 단건 조회와 기간 목록 항목이 같은 형식이다.
 * 값은 모르는 값이 와도 역직렬화가 실패하지 않도록 문자열로 받는다. REJECTED 거래는 entries가 빈 배열
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerTransactionDetailResponse(UUID transactionId, String status, String referenceType,
                                              UUID referenceId, List<Entry> entries) {

    /** amount는 누락을 알 수 있도록 Long으로 받는다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String ownerType, UUID walletId, String accountType, String currency, String direction,
                        Long amount, String entryType) {
    }
}
