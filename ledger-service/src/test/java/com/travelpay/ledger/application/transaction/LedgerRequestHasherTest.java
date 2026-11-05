package com.travelpay.ledger.application.transaction;

import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.EntryType;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import com.travelpay.ledger.presentation.transaction.dto.RegisterTransactionRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 요청 DTO가 entryType을 enum으로 받아도 기존 문자열 요청과 같은 해시가 나와야 한다 (저장된 해시와 호환) */
class LedgerRequestHasherTest {

    @ParameterizedTest
    @EnumSource(EntryType.class)
    void enum으로_받은_요청은_같은_문자열_요청과_해시가_같다(EntryType entryType) {
        UUID referenceId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        RegisterTransactionRequest request = new RegisterTransactionRequest(
                ReferenceType.CHARGE, referenceId, null, null, null, List.of(
                new RegisterTransactionRequest.EntryRequest(OwnerType.SYSTEM, null, AccountType.CHARGE_SOURCE,
                        Currency.KRW, Direction.DEBIT, 1_000L, entryType),
                new RegisterTransactionRequest.EntryRequest(OwnerType.USER, walletId, null,
                        Currency.KRW, Direction.CREDIT, 1_000L, entryType)));
        RegisterTransactionCommand stringCommand = new RegisterTransactionCommand(
                ReferenceType.CHARGE, referenceId, null, null, null, List.of(
                new RegisterTransactionCommand.Entry(OwnerType.SYSTEM, null, AccountType.CHARGE_SOURCE,
                        Currency.KRW, Direction.DEBIT, 1_000L, literal(entryType)),
                new RegisterTransactionCommand.Entry(OwnerType.USER, walletId, null,
                        Currency.KRW, Direction.CREDIT, 1_000L, literal(entryType))));

        assertEquals(LedgerRequestHasher.hash(stringCommand), LedgerRequestHasher.hash(request.toCommand()));
    }

    /** enum의 name()에 기대지 않고 V5 CHECK에 적힌 문자열 그대로 */
    private static String literal(EntryType entryType) {
        return switch (entryType) {
            case CHARGE_OUT -> "CHARGE_OUT";
            case CHARGE_IN -> "CHARGE_IN";
            case PAYMENT_OUT -> "PAYMENT_OUT";
            case PAYMENT_IN -> "PAYMENT_IN";
            case PAYMENT_REFUND_OUT -> "PAYMENT_REFUND_OUT";
            case PAYMENT_REFUND_IN -> "PAYMENT_REFUND_IN";
            case EXCHANGE_OUT -> "EXCHANGE_OUT";
            case EXCHANGE_IN -> "EXCHANGE_IN";
            case EXCHANGE_FEE -> "EXCHANGE_FEE";
            case TRANSFER_OUT -> "TRANSFER_OUT";
            case TRANSFER_IN -> "TRANSFER_IN";
        };
    }
}
