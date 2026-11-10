package com.travelpay.exchange.domain.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 원장에 기록할 환전 거래 한 건입니다. reference는 EXCHANGE + 주문 ID이며, 원장은 이 값으로 같은 거래를 한 번만 기록합니다.
 * 원장은 금액·계정·entryType으로 요청 해시를 만들어, 같은 reference로 내용이 다르면 409를 줍니다.
 * 그래서 항목은 저장된 주문 값으로만 만들고, 다시 보낼 때도 같은 항목이 나와야 합니다.
 *
 * @param orderId reference ID로 쓰는 환전 주문 ID
 * @param userId  원장 거래에 남길 회원 ID
 */
public record LedgerPosting(UUID orderId, UUID userId, List<LedgerEntry> entries) {

    public LedgerPosting {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(userId, "userId");
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new IllegalArgumentException("ledger posting needs at least two entries");
        }
    }
}
