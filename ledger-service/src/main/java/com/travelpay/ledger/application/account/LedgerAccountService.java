package com.travelpay.ledger.application.account;

import com.travelpay.common.error.BusinessException;
import com.travelpay.ledger.application.account.dto.OpenWalletAccountsCommand;
import com.travelpay.ledger.application.account.dto.WalletAccountsResult;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.account.LedgerAccount;
import com.travelpay.ledger.domain.account.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerAccountService {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    private static final int TX_TIMEOUT_SECONDS = 3;

    private final LedgerAccountRepository accountRepository;

    /**
     * 지갑의 통화별 WALLET 계정을 개설한다. 이미 있는 통화는 건너뛰므로 재시도·동시 요청에도 멱등.
     * 재조회 결과가 통화 수와 다르면 전체 롤백한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public WalletAccountsResult openWalletAccounts(OpenWalletAccountsCommand command) {
        UUID walletId = command.walletId();
        boolean created = false;
        for (Currency currency : Currency.values()) {
            created |= accountRepository.addWalletAccountIfAbsent(walletId, currency);
        }
        List<LedgerAccount> accounts = accountRepository.findWalletAccounts(walletId);
        if (accounts.size() != Currency.values().length) {
            throw new IllegalStateException("지갑 계정 개설 결과가 올바르지 않습니다.");
        }
        return WalletAccountsResult.of(created, accounts);
    }

    /** 계정 상태와 무관하게 그대로 돌려준다 (사용 가능 여부 판단은 호출자 몫) */
    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public WalletAccountsResult getWalletAccounts(UUID walletId) {
        Objects.requireNonNull(walletId, "walletId");
        List<LedgerAccount> accounts = accountRepository.findWalletAccounts(walletId);
        if (accounts.isEmpty()) {
            throw new BusinessException(LedgerErrorCode.LEDGER_ACCOUNT_NOT_FOUND);
        }
        return WalletAccountsResult.of(false, accounts);
    }
}
