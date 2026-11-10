package com.travelpay.exchange.application.wallet;

import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import com.travelpay.exchange.domain.wallet.WalletLedgerUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 환전에 쓸 지갑을 확인합니다. 환전 신청, 예약 생성, 예약 체결이 같은 규칙을 씁니다.
 * 신청 지갑이 본인 지갑이고, 보내는 통화로 돈을 빼고 받는 통화로 돈을 받을 수 있어야 합니다.
 * 잔액은 보지 않습니다. 잔액 판단은 원장이 거래를 기록할 때 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeWalletChecker {

    private final WalletBalanceReader walletBalanceReader;

    /**
     * @throws BusinessException 본인 지갑이 아니거나 없음(404 EXCHANGE_WALLET_NOT_FOUND),
     *                           보내거나 받을 수 없는 지갑(409 EXCHANGE_WALLET_NOT_AVAILABLE),
     *                           원장 장애로 지갑이 잔액을 못 읽음(503 EXCHANGE_LEDGER_UNAVAILABLE),
     *                           그 밖의 지갑 서비스 실패(503 EXCHANGE_WALLET_SERVICE_UNAVAILABLE)
     */
    public void check(UUID userId, UUID walletId, Currency from, Currency to) {
        WalletBalances wallet;
        try {
            wallet = walletBalanceReader.read(userId)
                    .orElseThrow(() -> new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_FOUND));
        } catch (WalletLedgerUnavailableException e) {
            // 원장이 꺼져 있으면 지갑이 잔액을 읽지 못해 여기서 막힌다. 받아 두면 원장이 돌아온 뒤 오래된 환율로 체결되므로 거절한다
            log.warn("원장 장애로 지갑을 확인하지 못했습니다: userId={}, reason={}", userId, e.getMessage());
            throw new BusinessException(ExchangeOrderErrorCode.LEDGER_UNAVAILABLE, e);
        } catch (WalletBalanceFetchException e) {
            log.warn("지갑을 확인하지 못했습니다: userId={}, reason={}", userId, e.getMessage());
            throw new BusinessException(ExchangeOrderErrorCode.WALLET_SERVICE_UNAVAILABLE, e);
        }
        // 다른 사람의 지갑 ID는 지갑 존재를 알리지 않도록 지갑 없음과 같게 다룹니다.
        if (!wallet.walletId().equals(walletId)) {
            throw new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_FOUND);
        }
        if (!wallet.canSpend(from) || !wallet.canReceive(to)) {
            throw new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE);
        }
    }
}
