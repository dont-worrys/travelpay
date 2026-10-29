package com.travelpay.wallet.application.wallet;

import com.travelpay.common.error.BusinessException;
import com.travelpay.wallet.application.wallet.dto.LedgerAccount;
import com.travelpay.wallet.application.wallet.dto.OpenWalletResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.wallet.Wallet;
import com.travelpay.wallet.domain.wallet.WalletCurrency;
import com.travelpay.wallet.domain.wallet.WalletCurrencyRepository;
import com.travelpay.wallet.domain.wallet.WalletRepository;
import com.travelpay.wallet.domain.wallet.WalletStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 지갑 개설·조회의 DB 트랜잭션 단계. 원장 호출이 트랜잭션 안에 들어가지 않도록
 * WalletService(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다.
 */
@Component
@RequiredArgsConstructor
public class WalletTransactionSteps {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    static final int TX_TIMEOUT_SECONDS = 3;

    private final WalletRepository walletRepository;
    private final WalletCurrencyRepository walletCurrencyRepository;

    /**
     * 1단계: 지갑을 PENDING으로 추가(이미 있으면 그대로)하고 재조회한다.
     * ACTIVE면 통화 목록까지 채워 돌려주고, PENDING이면 통화 목록은 비어 있다.
     * 다른 트랜잭션이 커밋한 행을 재조회에서 봐야 하므로 READ COMMITTED 전제.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public OpenWalletResult prepareOpen(UUID userId) {
        walletRepository.addIfAbsent(userId);
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("추가한 지갑을 다시 조회하지 못했습니다."));
        requireOpenable(wallet);
        if (!wallet.isActive()) {
            return new OpenWalletResult(false, wallet.getId(), wallet.getStatus(), List.of());
        }
        Map<Currency, UUID> accountIds = loadCompleteCurrencies(wallet.getId());
        return new OpenWalletResult(false, wallet.getId(), wallet.getStatus(), List.copyOf(accountIds.keySet()));
    }

    /**
     * 3단계: 원장 계정으로 통화별 지갑을 추가(이미 있으면 그대로)하고, 모든 통화가 원장 계정과 맞을 때만 활성화한다.
     * 지갑 행을 잠가 같은 지갑의 동시 완료를 순서대로 처리한다 (한 요청만 활성화, 나머지는 이미 ACTIVE로 봄).
     * 다른 트랜잭션이 커밋한 행을 재조회에서 봐야 하므로 READ COMMITTED 전제.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public OpenWalletResult completeOpen(UUID userId, List<LedgerAccount> ledgerAccounts) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("개설 중인 지갑을 찾지 못했습니다."));
        requireOpenable(wallet);
        boolean wasPending = wallet.getStatus() == WalletStatus.PENDING;

        for (LedgerAccount account : ledgerAccounts) {
            walletCurrencyRepository.addIfAbsent(wallet.getId(), account.currency(), account.accountId());
        }
        Map<Currency, UUID> accountIds = loadCompleteCurrencies(wallet.getId());
        for (LedgerAccount account : ledgerAccounts) {
            if (!account.accountId().equals(accountIds.get(account.currency()))) {
                throw new IllegalStateException("통화별 지갑과 원장 계정이 일치하지 않습니다.");
            }
        }

        if (wasPending) {
            wallet.activate();
        }
        return new OpenWalletResult(wasPending, wallet.getId(), wallet.getStatus(), List.copyOf(accountIds.keySet()));
    }

    /** 잔액 조회 전 확인. 원장 호출 전에 커밋되도록 따로 둔다 */
    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public UUID findActiveWalletId(UUID userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
        if (!wallet.isActive()) {
            throw new BusinessException(WalletErrorCode.WALLET_NOT_ACTIVE);
        }
        return wallet.getId();
    }

    private void requireOpenable(Wallet wallet) {
        if (wallet.getStatus() != WalletStatus.PENDING && wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BusinessException(WalletErrorCode.WALLET_NOT_ACTIVE);
        }
    }

    /** 지원 통화가 하나씩 모두 있어야 한다. 통화 순으로 돌려준다 */
    private Map<Currency, UUID> loadCompleteCurrencies(UUID walletId) {
        List<WalletCurrency> rows = walletCurrencyRepository.findByWalletId(walletId);
        Map<Currency, UUID> accountIds = new EnumMap<>(Currency.class);
        rows.forEach(row -> accountIds.put(row.getCurrency(), row.getLedgerAccountId()));
        if (rows.size() != Currency.values().length || accountIds.size() != Currency.values().length) {
            throw new IllegalStateException("통화별 지갑 구성이 올바르지 않습니다.");
        }
        return accountIds;
    }
}
