package com.travelpay.payment.infra.client.transfer;

import java.util.UUID;

/** Wallet 내부 API 응답입니다. 지갑 상태는 새 상태가 추가돼도 역직렬화가 실패하지 않도록 문자열로 받습니다. */
public record WalletResponse(UUID walletId, String status) {
}
