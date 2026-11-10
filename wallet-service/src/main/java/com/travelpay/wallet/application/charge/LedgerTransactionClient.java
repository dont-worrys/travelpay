package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.application.charge.dto.LedgerPostResult;

import java.util.UUID;

/**
 * 원장 충전 거래 등록 (reference = CHARGE/chargeId). 같은 reference 재요청은 처음 결과를 돌려받는다.
 * 호출 실패도 예외가 아니라 LedgerPostResult(Unknown·Invalid)로 알린다.
 */
public interface LedgerTransactionClient {

    LedgerPostResult postCharge(UUID chargeId, UUID userId, UUID walletId, long amount);
}
