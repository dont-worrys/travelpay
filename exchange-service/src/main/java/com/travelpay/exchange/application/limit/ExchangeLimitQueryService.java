package com.travelpay.exchange.application.limit;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.AmountRange;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 보내는 통화별 1회 환전 한도와 잔액 기준 환전 가능 금액을 조회합니다.
 * 견적 발급이 검사하는 QuotePolicy를 그대로 읽어, 안내하는 한도와 실제 검사하는 한도가 같게 합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeLimitQueryService {

    private final QuotePolicy quotePolicy;
    private final WalletBalanceReader walletBalanceReader;

    /**
     * 지원 통화 전체의 한도입니다. 응답 순서가 바뀌지 않도록 통화 선언 순서(KRW, JPY, CNY)로 돌려줍니다.
     * 지갑 잔액을 가져오지 못해도 한도 안내는 할 수 있으므로, 실패하면 잔액 없이 한도만 돌려줍니다.
     */
    public List<ExchangeLimit> findAll(UUID userId) {
        Optional<WalletBalances> wallet;
        try {
            wallet = walletBalanceReader.read(userId);
        } catch (WalletBalanceFetchException e) {
            log.warn("지갑 잔액을 가져오지 못해 한도만 응답합니다: userId={}, reason={}", userId, e.getMessage());
            return Arrays.stream(Currency.values())
                    .map(currency -> ExchangeLimit.withoutBalance(rangeOf(currency)))
                    .toList();
        }
        return Arrays.stream(Currency.values())
                .map(currency -> limitOf(currency, wallet))
                .toList();
    }

    /** 지갑이나 그 통화 계정이 없으면 잔액 0, 지갑이나 계정이 ACTIVE가 아니면 환전 가능 금액 0입니다. */
    private ExchangeLimit limitOf(Currency currency, Optional<WalletBalances> wallet) {
        Money zero = new Money(currency, 0);
        Money balance = wallet.flatMap(balances -> balances.account(currency))
                .map(WalletBalances.Account::balance)
                .orElse(zero);
        boolean canSpend = wallet.map(balances -> balances.canSpend(currency)).orElse(false);
        Money available = canSpend ? quotePolicy.availableAmount(balance) : zero;
        return new ExchangeLimit(rangeOf(currency), balance, available);
    }

    private AmountRange rangeOf(Currency currency) {
        return quotePolicy.limits().get(currency);
    }
}
