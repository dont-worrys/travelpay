package com.travelpay.payment.transfer.application;

import java.util.List;
import java.util.UUID;

/** 오래 확정되지 않은 처리 중 송금의 수와 오래된 순 ID 일부입니다. */
public record StuckTransfers(long count, List<UUID> sampleIds) {

    public StuckTransfers {
        sampleIds = List.copyOf(sampleIds);
    }
}
