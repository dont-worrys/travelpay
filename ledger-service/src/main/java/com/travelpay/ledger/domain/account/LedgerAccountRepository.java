package com.travelpay.ledger.domain.account;

import com.travelpay.ledger.domain.Currency;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository {

    /** 지갑의 해당 통화 WALLET 계정이 없을 때만 추가한다. 새로 추가했으면 true */
    boolean addWalletAccountIfAbsent(UUID walletId, Currency currency);

    /** 지갑의 WALLET 계정 목록 (상태 무관) */
    List<LedgerAccount> findWalletAccounts(UUID walletId);

    /** 잠금 전 조회라 ID만 돌려준다 (엔티티를 미리 올리면 잠금 후 최신 잔액을 못 읽음) */
    Optional<UUID> findUserWalletAccountId(UUID walletId, Currency currency);

    Optional<UUID> findSystemAccountId(AccountType accountType, Currency currency);

    /** 계정 ID 순으로 FOR UPDATE 잠금 후 최신 행을 돌려준다 */
    List<LedgerAccount> lockAllInIdOrder(Collection<UUID> accountIds);
}
