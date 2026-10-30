package com.travelpay.wallet.presentation.wallet.dto;

import com.travelpay.wallet.application.wallet.dto.WalletResult;
import com.travelpay.wallet.domain.wallet.WalletStatus;

import java.util.UUID;

/** 송금 파트와 합의한 계약. 필드 이름을 바꾸지 않는다 */
public record InternalWalletResponse(UUID walletId, WalletStatus status) {

    public static InternalWalletResponse from(WalletResult result) {
        return new InternalWalletResponse(result.walletId(), result.status());
    }
}
