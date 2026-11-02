package com.travelpay.exchange.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.travelpay.exchange.domain.ledger.LedgerDirection;
import com.travelpay.exchange.domain.ledger.LedgerEntry;
import com.travelpay.exchange.domain.ledger.LedgerEntryType;
import com.travelpay.exchange.domain.ledger.LedgerPosting;
import com.travelpay.exchange.domain.ledger.LedgerSystemAccount;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuoteType;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 매매기준율 JPY 838.30(unit 100), 수수료율 1% 기준입니다. */
class ExchangeOrderLedgerPostingTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-29T03:00:00Z");

    @Test
    void exchangeIsSixEntriesPairedByEntryType() {
        LedgerPosting posting = savedOrder(Currency.KRW, Currency.JPY, "100000").toLedgerPosting();

        assertEquals(ORDER_ID, posting.orderId());
        assertEquals(USER_ID, posting.userId());
        assertEquals(List.of(
                LedgerEntry.wallet(WALLET_ID, krw(100000), LedgerDirection.DEBIT, LedgerEntryType.EXCHANGE_OUT),
                LedgerEntry.system(LedgerSystemAccount.FX_POSITION, krw(100000), LedgerDirection.CREDIT,
                        LedgerEntryType.EXCHANGE_OUT),
                LedgerEntry.wallet(WALLET_ID, krw(1000), LedgerDirection.DEBIT, LedgerEntryType.EXCHANGE_FEE),
                LedgerEntry.system(LedgerSystemAccount.FEE_REVENUE, krw(1000), LedgerDirection.CREDIT,
                        LedgerEntryType.EXCHANGE_FEE),
                LedgerEntry.system(LedgerSystemAccount.FX_POSITION, jpy(11928), LedgerDirection.DEBIT,
                        LedgerEntryType.EXCHANGE_IN),
                LedgerEntry.wallet(WALLET_ID, jpy(11928), LedgerDirection.CREDIT, LedgerEntryType.EXCHANGE_IN)),
                posting.entries());
    }

    @Test
    void reExchangeTakesFeeInSendingCurrency() {
        LedgerPosting posting = savedOrder(Currency.JPY, Currency.KRW, "1000").toLedgerPosting();

        assertEquals(List.of(
                LedgerEntry.wallet(WALLET_ID, jpy(1000), LedgerDirection.DEBIT, LedgerEntryType.EXCHANGE_OUT),
                LedgerEntry.system(LedgerSystemAccount.FX_POSITION, jpy(1000), LedgerDirection.CREDIT,
                        LedgerEntryType.EXCHANGE_OUT),
                LedgerEntry.wallet(WALLET_ID, jpy(10), LedgerDirection.DEBIT, LedgerEntryType.EXCHANGE_FEE),
                LedgerEntry.system(LedgerSystemAccount.FEE_REVENUE, jpy(10), LedgerDirection.CREDIT,
                        LedgerEntryType.EXCHANGE_FEE),
                LedgerEntry.system(LedgerSystemAccount.FX_POSITION, krw(8383), LedgerDirection.DEBIT,
                        LedgerEntryType.EXCHANGE_IN),
                LedgerEntry.wallet(WALLET_ID, krw(8383), LedgerDirection.CREDIT, LedgerEntryType.EXCHANGE_IN)),
                posting.entries());
    }

    @Test
    void debitsEqualCreditsPerCurrencyAndPerEntryType() {
        LedgerPosting posting = savedOrder(Currency.KRW, Currency.JPY, "100000").toLedgerPosting();

        assertBalanced(posting.entries().stream()
                .collect(Collectors.groupingBy(entry -> entry.money().currency())));
        assertBalanced(posting.entries().stream().collect(Collectors.groupingBy(LedgerEntry::entryType)));
    }

    @Test
    void walletIsDebitedTotalAndCreditedReceivedAmount() {
        ExchangeOrder order = savedOrder(Currency.KRW, Currency.JPY, "100000");

        long walletDebit = sum(order.toLedgerPosting().entries(), LedgerDirection.DEBIT, true);
        long walletCredit = sum(order.toLedgerPosting().entries(), LedgerDirection.CREDIT, true);

        assertEquals(order.totalDebitMoney().minorUnits(), walletDebit);
        assertEquals(order.toMoney().minorUnits(), walletCredit);
    }

    @Test
    void zeroFeeLeavesOutFeeEntries() {
        ExchangeOrder order = savedOrder(Currency.KRW, Currency.JPY, "100000");
        ReflectionTestUtils.setField(order, "feeAmount", 0L);
        ReflectionTestUtils.setField(order, "totalDebitAmount", 100000L);

        List<LedgerEntry> entries = order.toLedgerPosting().entries();

        assertEquals(4, entries.size());
        assertEquals(0, entries.stream().filter(entry -> entry.entryType() == LedgerEntryType.EXCHANGE_FEE).count());
    }

    @Test
    void sameOrderAlwaysBuildsSamePosting() {
        ExchangeOrder order = savedOrder(Currency.KRW, Currency.JPY, "100000");

        assertEquals(order.toLedgerPosting(), order.toLedgerPosting());
    }

    @Test
    void needsSavedOrder() {
        ExchangeOrder order = savedOrder(Currency.KRW, Currency.JPY, "100000");
        ReflectionTestUtils.setField(order, "id", null);

        assertThrows(IllegalStateException.class, order::toLedgerPosting);
    }

    private static <K> void assertBalanced(Map<K, List<LedgerEntry>> groups) {
        groups.forEach((key, entries) -> assertEquals(sum(entries, LedgerDirection.DEBIT, null),
                sum(entries, LedgerDirection.CREDIT, null), "unbalanced: " + key));
    }

    /** @param wallet true면 지갑 항목만, null이면 전체 */
    private static long sum(List<LedgerEntry> entries, LedgerDirection direction, Boolean wallet) {
        return entries.stream()
                .filter(entry -> entry.direction() == direction)
                .filter(entry -> wallet == null || entry.isWalletEntry() == wallet)
                .mapToLong(entry -> entry.money().minorUnits())
                .sum();
    }

    private static ExchangeOrder savedOrder(Currency from, Currency to, String amount) {
        ExchangeQuote quote = ExchangeQuote.issue(USER_ID, QuoteType.USER, new QuoteCurrencies(from, to),
                Money.of(from, new BigDecimal(amount)), QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"),
                QuoteFixtures.policy(), ISSUED_AT);
        ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(quote, "createdAt", ISSUED_AT);
        ExchangeOrder order = OrderFixtures.placed(quote, WALLET_ID, "key-1");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    private static Money krw(long amount) {
        return new Money(Currency.KRW, amount);
    }

    private static Money jpy(long amount) {
        return new Money(Currency.JPY, amount);
    }
}
