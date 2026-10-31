package com.travelpay.exchange.application.limit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import org.junit.jupiter.api.Test;

import static com.travelpay.exchange.domain.quote.QuoteFixtures.range;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExchangeLimitQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** 금액은 최소 단위입니다. KRW 50만 원은 수수료 1%를 빼고 495,049원까지 보낼 수 있습니다. */
    @Test
    void addsBalanceAndAvailableAmountInDeclarationOrder() {
        WalletBalances wallet = wallet(true, Map.of(
                Currency.CNY, account(Currency.CNY, 10151, true),
                Currency.KRW, account(Currency.KRW, 500_000, true),
                Currency.JPY, account(Currency.JPY, 0, true)));

        List<ExchangeLimit> limits = serviceReading(wallet).findAll(USER_ID);

        assertEquals(List.of(
                new ExchangeLimit(range(Currency.KRW, 1000, 2_000_000), krw(500_000), krw(495_049)),
                new ExchangeLimit(range(Currency.JPY, 100, 200_000), jpy(0), jpy(0)),
                new ExchangeLimit(range(Currency.CNY, 1000, 1_000_000), cny(10151), cny(10050))
        ), limits);
    }

    /** 잔액은 그대로 보여 주고, 돈을 뺄 수 없는 계정만 환전 가능 금액이 0입니다. */
    @Test
    void availableAmountIsZeroWhenWalletOrAccountIsNotActive() {
        WalletBalances suspendedCny = wallet(true, Map.of(
                Currency.KRW, account(Currency.KRW, 500_000, true),
                Currency.JPY, account(Currency.JPY, 10_000, true),
                Currency.CNY, account(Currency.CNY, 10151, false)));
        WalletBalances suspendedWallet = wallet(false, Map.of(
                Currency.KRW, account(Currency.KRW, 500_000, true)));

        assertEquals(new ExchangeLimit(range(Currency.CNY, 1000, 1_000_000), cny(10151), cny(0)),
                serviceReading(suspendedCny).findAll(USER_ID).get(2));
        assertEquals(new ExchangeLimit(range(Currency.KRW, 1000, 2_000_000), krw(500_000), krw(0)),
                serviceReading(suspendedWallet).findAll(USER_ID).get(0));
    }

    /** 개설 중(PENDING)인 지갑은 통화 계정이 없어 빈 목록으로 옵니다. */
    @Test
    void missingAccountMeansZeroBalance() {
        List<ExchangeLimit> limits = serviceReading(wallet(false, Map.of())).findAll(USER_ID);

        assertEquals(new ExchangeLimit(range(Currency.JPY, 100, 200_000), jpy(0), jpy(0)), limits.get(1));
    }

    /** 지갑이 없는 것은 확인된 사실이라 실패와 달리 0으로 응답합니다. */
    @Test
    void missingWalletMeansZeroBalance() {
        ExchangeLimitQueryService service =
                new ExchangeLimitQueryService(QuoteFixtures.policy(), userId -> Optional.empty());

        assertEquals(new ExchangeLimit(range(Currency.KRW, 1000, 2_000_000), krw(0), krw(0)),
                service.findAll(USER_ID).get(0));
    }

    @Test
    void returnsOnlyLimitsWhenWalletCannotBeRead() {
        WalletBalanceReader failing = userId -> {
            throw new WalletBalanceFetchException("지갑 서비스 응답 없음");
        };

        List<ExchangeLimit> limits = new ExchangeLimitQueryService(QuoteFixtures.policy(), failing).findAll(USER_ID);

        assertEquals(List.of(
                ExchangeLimit.withoutBalance(range(Currency.KRW, 1000, 2_000_000)),
                ExchangeLimit.withoutBalance(range(Currency.JPY, 100, 200_000)),
                ExchangeLimit.withoutBalance(range(Currency.CNY, 1000, 1_000_000))
        ), limits);
    }

    private static ExchangeLimitQueryService serviceReading(WalletBalances wallet) {
        return new ExchangeLimitQueryService(QuoteFixtures.policy(), userId -> Optional.of(wallet));
    }

    private static WalletBalances wallet(boolean active, Map<Currency, WalletBalances.Account> accounts) {
        return new WalletBalances(WALLET_ID, active, accounts);
    }

    private static WalletBalances.Account account(Currency currency, long balance, boolean active) {
        return new WalletBalances.Account(new Money(currency, balance), active);
    }

    private static Money krw(long amount) {
        return new Money(Currency.KRW, amount);
    }

    private static Money jpy(long amount) {
        return new Money(Currency.JPY, amount);
    }

    private static Money cny(long amount) {
        return new Money(Currency.CNY, amount);
    }
}
