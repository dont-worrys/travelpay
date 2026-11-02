package com.travelpay.exchange.domain.ledger;

import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerEntryTest {

    private static final Money KRW_1000 = new Money(Currency.KRW, 1000);

    @Test
    void needsExactlyOneAccount() {
        assertThrows(IllegalArgumentException.class, () -> new LedgerEntry(null, null, KRW_1000,
                LedgerDirection.DEBIT, LedgerEntryType.EXCHANGE_OUT));
        assertThrows(IllegalArgumentException.class, () -> new LedgerEntry(UUID.randomUUID(),
                LedgerSystemAccount.FX_POSITION, KRW_1000, LedgerDirection.DEBIT, LedgerEntryType.EXCHANGE_OUT));
    }

    @Test
    void rejectsZeroAmount() {
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.wallet(UUID.randomUUID(),
                new Money(Currency.KRW, 0), LedgerDirection.DEBIT, LedgerEntryType.EXCHANGE_FEE));
    }

    @Test
    void postingNeedsAtLeastTwoEntries() {
        LedgerEntry one = LedgerEntry.system(LedgerSystemAccount.FX_POSITION, KRW_1000, LedgerDirection.CREDIT,
                LedgerEntryType.EXCHANGE_OUT);

        assertThrows(IllegalArgumentException.class,
                () -> new LedgerPosting(UUID.randomUUID(), UUID.randomUUID(), List.of(one)));
    }
}
