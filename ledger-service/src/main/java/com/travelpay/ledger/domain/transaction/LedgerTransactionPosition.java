package com.travelpay.ledger.domain.transaction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 기간 목록의 정렬 기준 (createdAt, id) 위치. 다음 페이지는 이 위치보다 뒤의 행부터 읽는다 */
public record LedgerTransactionPosition(Instant createdAt, UUID id) {

    public LedgerTransactionPosition {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(id, "id");
    }
}
