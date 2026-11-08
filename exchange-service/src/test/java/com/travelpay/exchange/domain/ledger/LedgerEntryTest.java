package com.travelpay.exchange.domain.ledger;

import java.util.List;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerEntryTest {

    private static final Money KRW_1000 = new Money(Currency.KRW, 1000);

    @Test
    void lookupEntrySetsWalletAccountTypeForUserAndSystemAccountForSystem() {
        UUID walletId = UUID.randomUUID();
        LedgerEntry userEntry = LedgerEntry.wallet(walletId, KRW_1000, LedgerDirection.DEBIT,
                LedgerEntryType.EXCHANGE_OUT);
        LedgerEntry systemEntry = LedgerEntry.system(LedgerSystemAccount.FX_POSITION, KRW_1000,
                LedgerDirection.CREDIT, LedgerEntryType.EXCHANGE_OUT);

        List<LedgerLookupResult.Entry> converted = LedgerLookupResult.Entry.fromEntries(List.of(userEntry, systemEntry));

        assertEquals(new LedgerLookupResult.Entry("USER", walletId, "WALLET", "KRW", "DEBIT", 1000, "EXCHANGE_OUT"),
                converted.get(0));
        assertEquals(new LedgerLookupResult.Entry("SYSTEM", null, "FX_POSITION", "KRW", "CREDIT", 1000, "EXCHANGE_OUT"),
                converted.get(1));
        assertNull(converted.get(1).walletId());
    }

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
