package com.travelpay.wallet.application.wallet;

import com.travelpay.common.error.BusinessException;
import com.travelpay.wallet.application.wallet.dto.LedgerAccount;
import com.travelpay.wallet.application.wallet.dto.OpenWalletCommand;
import com.travelpay.wallet.application.wallet.dto.OpenWalletResult;
import com.travelpay.wallet.application.wallet.dto.WalletBalancesResult;
import com.travelpay.wallet.application.wallet.dto.WalletResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.wallet.Wallet;
import com.travelpay.wallet.domain.wallet.WalletCurrency;
import com.travelpay.wallet.domain.wallet.WalletCurrencyRepository;
import com.travelpay.wallet.domain.wallet.WalletRepository;
import com.travelpay.wallet.domain.wallet.WalletStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletTransactionSteps steps;
    private final WalletRepository walletRepository;
    private final WalletCurrencyRepository walletCurrencyRepository;
    private final LedgerAccountClient ledgerAccountClient;

    /**
     * 지갑 개설. 원장 호출은 트랜잭션 밖에서 한다.
     * 원장 장애로 중단되면 지갑은 PENDING으로 남고, 같은 요청을 다시 보내면 빠진 단계만 수행해 ACTIVE가 된다.
     */
    public OpenWalletResult openWallet(OpenWalletCommand command) {
        UUID userId = command.userId();
        OpenWalletResult prepared = steps.prepareOpen(userId);
        if (prepared.status() == WalletStatus.ACTIVE) {
            return prepared;
        }
        UUID walletId = prepared.walletId();
        List<LedgerAccount> accounts = callLedger(walletId, userId,
                () -> ledgerAccountClient.openAccounts(walletId));
        return steps.completeOpen(userId, accounts);
    }

    @Transactional(readOnly = true, timeout = WalletTransactionSteps.TX_TIMEOUT_SECONDS)
    public WalletResult getWallet(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
        List<Currency> currencies = walletCurrencyRepository.findByWalletId(wallet.getId()).stream()
                .map(WalletCurrency::getCurrency)
                .sorted()
                .toList();
        return new WalletResult(wallet.getId(), wallet.getStatus(), currencies);
    }

    /** 원장 잔액을 그대로 돌려준다 (캐시 없음) */
    public WalletBalancesResult getBalances(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        UUID walletId = steps.findActiveWalletId(userId);
        List<LedgerAccount> accounts = callLedger(walletId, userId,
                () -> ledgerAccountClient.findAccounts(walletId))
                .orElseThrow(() -> {
                    log.error("ACTIVE 지갑의 원장 계정 없음 walletId={} userId={}", walletId, userId);
                    return new IllegalStateException("지갑과 원장 계정 상태가 일치하지 않습니다.");
                });
        return new WalletBalancesResult(accounts.stream()
                .map(account -> new WalletBalancesResult.Balance(account.currency(), account.balance()))
                .toList());
    }

    /** 재시도로 풀리지 않는 원장 응답은 지갑·회원 식별자와 함께 ERROR로 남긴다. 원장 응답 내용은 남기지 않는다 */
    private <T> T callLedger(UUID walletId, UUID userId, Supplier<T> call) {
        try {
            return call.get();
        } catch (LedgerResponseException e) {
            log.error("원장 응답 처리 불가 walletId={} userId={} exception={}", walletId, userId, e.causeClassName());
            throw e;
        }
    }
}
