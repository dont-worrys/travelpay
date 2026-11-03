package com.travelpay.payment.application.transfer.client;

import java.util.UUID;

/** 송금에 쓰는 지갑 정보입니다. 상태는 PENDING·ACTIVE·SUSPENDED·CLOSED이며, 새 상태가 추가돼도 처리할 수 있도록 문자열로 둡니다. */
public record TransferWallet(UUID walletId, String status) {

    private static final String ACTIVE = "ACTIVE";

    /** 송금인·수취인 모두 ACTIVE 지갑만 송금할 수 있습니다. */
    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
