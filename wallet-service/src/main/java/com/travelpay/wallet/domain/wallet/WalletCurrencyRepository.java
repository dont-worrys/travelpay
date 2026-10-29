package com.travelpay.wallet.domain.wallet;

import com.travelpay.wallet.domain.Currency;

import java.util.List;
import java.util.UUID;

public interface WalletCurrencyRepository {

    /** 지갑의 해당 통화 행이 없을 때만 추가한다. 동시 요청에도 통화별 1건만 생긴다 */
    void addIfAbsent(UUID walletId, Currency currency, UUID ledgerAccountId);

    List<WalletCurrency> findByWalletId(UUID walletId);
}
