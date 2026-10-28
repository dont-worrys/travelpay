package com.travelpay.ledger.domain.account;

import com.travelpay.ledger.domain.Currency;

import java.util.List;
import java.util.UUID;

public interface LedgerAccountRepository {

    /** 지갑의 해당 통화 WALLET 계정이 없을 때만 추가한다. 새로 추가했으면 true */
    boolean addWalletAccountIfAbsent(UUID walletId, Currency currency);

    /** 지갑의 WALLET 계정 목록 (상태 무관) */
    List<LedgerAccount> findWalletAccounts(UUID walletId);
}
