package com.travelpay.wallet.application.wallet;

import com.travelpay.common.error.BusinessException;
import com.travelpay.wallet.application.wallet.WalletTransactionSteps.WalletCurrencyRows;
import com.travelpay.wallet.application.wallet.dto.InternalWalletBalancesResult;
import com.travelpay.wallet.application.wallet.dto.InternalWalletBalancesResult.Balance;
import com.travelpay.wallet.application.wallet.dto.LedgerAccount;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.wallet.Wallet;
import com.travelpay.wallet.domain.wallet.WalletCurrency;
import com.travelpay.wallet.domain.wallet.WalletCurrencyRepository;
import com.travelpay.wallet.domain.wallet.WalletRepository;
import com.travelpay.wallet.domain.wallet.WalletStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletServiceInternalBalancesTest {

    private final WalletTransactionSteps steps = mock(WalletTransactionSteps.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletCurrencyRepository walletCurrencyRepository = mock(WalletCurrencyRepository.class);
    private final LedgerAccountClient ledgerAccountClient = mock(LedgerAccountClient.class);
    private final WalletService walletService =
            new WalletService(steps, walletRepository, walletCurrencyRepository, ledgerAccountClient);

    private final UUID userId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    @Test
    void 지갑이_없으면_WALLET_NOT_FOUND() {
        WalletTransactionSteps realSteps = new WalletTransactionSteps(walletRepository, walletCurrencyRepository);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> realSteps.findWalletCurrencies(userId));

        assertSame(WalletErrorCode.WALLET_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void DB_단계는_지갑_상태로_막지_않고_통화_순으로_돌려준다() {
        WalletTransactionSteps realSteps = new WalletTransactionSteps(walletRepository, walletCurrencyRepository);
        Wallet wallet = Wallet.create(userId);
        wallet.changeStatus(WalletStatus.SUSPENDED);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletCurrencyRepository.findByWalletId(any())).thenReturn(List.of(
                row(Currency.CNY), row(Currency.KRW), row(Currency.JPY)));

        WalletCurrencyRows rows = realSteps.findWalletCurrencies(userId);

        assertEquals(WalletStatus.SUSPENDED, rows.status());
        assertEquals(List.of(Currency.KRW, Currency.JPY, Currency.CNY),
                rows.currencies().stream().map(WalletCurrency::getCurrency).toList());
    }

    @Test
    void 통화_행이_없으면_원장을_호출하지_않고_빈_목록() {
        when(steps.findWalletCurrencies(userId))
                .thenReturn(new WalletCurrencyRows(walletId, WalletStatus.PENDING, List.of()));

        InternalWalletBalancesResult result = walletService.getInternalBalances(userId);

        assertEquals(walletId, result.walletId());
        assertEquals(WalletStatus.PENDING, result.status());
        assertEquals(List.of(), result.balances());
        verify(ledgerAccountClient, never()).findAccounts(any());
    }

    @Test
    void 행이_있는_통화만_원장_잔액과_통화별_상태를_붙여_돌려준다() {
        WalletCurrency suspendedCny = row(Currency.CNY);
        suspendedCny.changeStatus(WalletStatus.SUSPENDED);
        when(steps.findWalletCurrencies(userId)).thenReturn(new WalletCurrencyRows(walletId, WalletStatus.ACTIVE,
                List.of(row(Currency.KRW), suspendedCny)));
        when(ledgerAccountClient.findAccounts(walletId)).thenReturn(Optional.of(List.of(
                account(Currency.KRW, 500_000), account(Currency.JPY, 1_000), account(Currency.CNY, 0))));

        InternalWalletBalancesResult result = walletService.getInternalBalances(userId);

        assertEquals(WalletStatus.ACTIVE, result.status());
        assertEquals(List.of(
                new Balance(Currency.KRW, 500_000, WalletStatus.ACTIVE),
                new Balance(Currency.CNY, 0, WalletStatus.SUSPENDED)), result.balances());
    }

    @Test
    void 원장_계정이_없으면_500() {
        when(steps.findWalletCurrencies(userId)).thenReturn(
                new WalletCurrencyRows(walletId, WalletStatus.ACTIVE, List.of(row(Currency.KRW))));
        when(ledgerAccountClient.findAccounts(walletId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> walletService.getInternalBalances(userId));
    }

    @Test
    void 행의_통화가_원장에_없으면_500() {
        when(steps.findWalletCurrencies(userId)).thenReturn(
                new WalletCurrencyRows(walletId, WalletStatus.ACTIVE, List.of(row(Currency.CNY))));
        when(ledgerAccountClient.findAccounts(walletId)).thenReturn(
                Optional.of(List.of(account(Currency.KRW, 100))));

        assertThrows(IllegalStateException.class, () -> walletService.getInternalBalances(userId));
    }

    private WalletCurrency row(Currency currency) {
        return WalletCurrency.create(walletId, currency, UUID.randomUUID());
    }

    private LedgerAccount account(Currency currency, long balance) {
        return new LedgerAccount(currency, UUID.randomUUID(), balance);
    }
}
