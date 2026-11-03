package com.travelpay.payment.transfer.client;

import java.util.UUID;

/** 지갑 상태는 PENDING·ACTIVE·SUSPENDED·CLOSED이며, 새 상태가 추가돼도 역직렬화가 실패하지 않도록 문자열로 받습니다. */
public record WalletResponse(UUID walletId, String status) {

    private static final String ACTIVE = "ACTIVE";

    /** 송금인·수취인 모두 ACTIVE 지갑만 송금할 수 있습니다. */
    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
