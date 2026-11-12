package com.travelpay.ledger.application.transaction;

import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.EntryType;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import com.travelpay.ledger.presentation.transaction.dto.RegisterTransactionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 요청 해시에 들어가는 필드와 빠지는 필드.
 * 포함(바뀌면 해시가 달라져 같은 reference 재요청이 409): referenceType, referenceId, originalTransactionId,
 * 항목의 ownerType·walletId·accountType·currency·direction·amount·entryType.
 * 제외(바뀌어도 같은 해시라 200): description, userId(송금 가이드의 "기록용" 계약), 항목 순서.
 * 요청 DTO가 entryType을 enum으로 받아도 기존 문자열 요청과 같은 해시가 나와야 한다 (저장된 해시와 호환)
 */
class LedgerRequestHasherTest {

    private static final UUID REFERENCE_ID = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORIGINAL_ID = UUID.randomUUID();

    private static final RegisterTransactionCommand.Entry SOURCE = new RegisterTransactionCommand.Entry(
            OwnerType.SYSTEM, null, AccountType.CHARGE_SOURCE, Currency.KRW, Direction.DEBIT, 1_000L, "CHARGE_IN");
    private static final RegisterTransactionCommand.Entry WALLET = new RegisterTransactionCommand.Entry(
            OwnerType.USER, WALLET_ID, null, Currency.KRW, Direction.CREDIT, 1_000L, "CHARGE_IN");

    private static final RegisterTransactionCommand BASE = new RegisterTransactionCommand(ReferenceType.CHARGE,
            REFERENCE_ID, USER_ID, "설명", ORIGINAL_ID, List.of(SOURCE, WALLET));

    static Stream<Arguments> includedFieldChanges() {
        return Stream.of(
                Arguments.of("referenceType", command(ReferenceType.TRANSFER, REFERENCE_ID, ORIGINAL_ID,
                        List.of(SOURCE, WALLET))),
                Arguments.of("referenceId", command(ReferenceType.CHARGE, UUID.randomUUID(), ORIGINAL_ID,
                        List.of(SOURCE, WALLET))),
                Arguments.of("originalTransactionId 값", command(ReferenceType.CHARGE, REFERENCE_ID,
                        UUID.randomUUID(), List.of(SOURCE, WALLET))),
                Arguments.of("originalTransactionId 없음", command(ReferenceType.CHARGE, REFERENCE_ID, null,
                        List.of(SOURCE, WALLET))),
                Arguments.of("항목 ownerType·계정 지정", withWallet(new RegisterTransactionCommand.Entry(
                        OwnerType.SYSTEM, null, AccountType.FEE_REVENUE, Currency.KRW, Direction.CREDIT, 1_000L,
                        "CHARGE_IN"))),
                Arguments.of("항목 walletId", withWallet(new RegisterTransactionCommand.Entry(
                        OwnerType.USER, UUID.randomUUID(), null, Currency.KRW, Direction.CREDIT, 1_000L,
                        "CHARGE_IN"))),
                Arguments.of("항목 accountType", command(ReferenceType.CHARGE, REFERENCE_ID, ORIGINAL_ID,
                        List.of(new RegisterTransactionCommand.Entry(OwnerType.SYSTEM, null, AccountType.FX_POSITION,
                                Currency.KRW, Direction.DEBIT, 1_000L, "CHARGE_IN"), WALLET))),
                Arguments.of("항목 currency", withWallet(new RegisterTransactionCommand.Entry(
                        OwnerType.USER, WALLET_ID, null, Currency.JPY, Direction.CREDIT, 1_000L, "CHARGE_IN"))),
                Arguments.of("항목 direction", withWallet(new RegisterTransactionCommand.Entry(
                        OwnerType.USER, WALLET_ID, null, Currency.KRW, Direction.DEBIT, 1_000L, "CHARGE_IN"))),
                Arguments.of("항목 amount", withWallet(new RegisterTransactionCommand.Entry(
                        OwnerType.USER, WALLET_ID, null, Currency.KRW, Direction.CREDIT, 1_001L, "CHARGE_IN"))),
                Arguments.of("항목 entryType", withWallet(new RegisterTransactionCommand.Entry(
                        OwnerType.USER, WALLET_ID, null, Currency.KRW, Direction.CREDIT, 1_000L, "CHARGE_OUT"))),
                Arguments.of("항목 하나 더", command(ReferenceType.CHARGE, REFERENCE_ID, ORIGINAL_ID,
                        List.of(SOURCE, WALLET, WALLET))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("includedFieldChanges")
    void 해시에_들어가는_필드가_바뀌면_해시가_달라진다(String field, RegisterTransactionCommand changed) {
        assertNotEquals(LedgerRequestHasher.hash(BASE), LedgerRequestHasher.hash(changed), field);
    }

    @Test
    void userId_설명_항목_순서만_다르면_해시가_같다() {
        String base = LedgerRequestHasher.hash(BASE);

        assertEquals(base, LedgerRequestHasher.hash(new RegisterTransactionCommand(ReferenceType.CHARGE,
                REFERENCE_ID, UUID.randomUUID(), "설명", ORIGINAL_ID, List.of(SOURCE, WALLET))), "userId");
        assertEquals(base, LedgerRequestHasher.hash(new RegisterTransactionCommand(ReferenceType.CHARGE,
                REFERENCE_ID, null, "설명", ORIGINAL_ID, List.of(SOURCE, WALLET))), "userId 없음");
        assertEquals(base, LedgerRequestHasher.hash(new RegisterTransactionCommand(ReferenceType.CHARGE,
                REFERENCE_ID, USER_ID, "다른 설명", ORIGINAL_ID, List.of(SOURCE, WALLET))), "description");
        assertEquals(base, LedgerRequestHasher.hash(new RegisterTransactionCommand(ReferenceType.CHARGE,
                REFERENCE_ID, USER_ID, "설명", ORIGINAL_ID, List.of(WALLET, SOURCE))), "항목 순서");
    }

    @Test
    void 취소_해시는_같은_reference의_등록_해시와_다르고_reference가_같으면_같다() {
        String cancel = LedgerRequestHasher.hashCancel(ReferenceType.CHARGE, REFERENCE_ID);

        // 등록 해시와 다르므로 취소 뒤 같은 reference의 등록은 409가 된다
        assertNotEquals(LedgerRequestHasher.hash(BASE), cancel);
        assertNotEquals(LedgerRequestHasher.hash(new RegisterTransactionCommand(ReferenceType.CHARGE,
                REFERENCE_ID, null, null, null, List.of(SOURCE, WALLET))), cancel, "원거래 없는 등록");
        assertEquals(64, cancel.length());
        assertEquals(cancel, LedgerRequestHasher.hashCancel(ReferenceType.CHARGE, REFERENCE_ID), "같은 입력");
        assertNotEquals(cancel, LedgerRequestHasher.hashCancel(ReferenceType.PAYMENT, REFERENCE_ID), "referenceType");
        assertNotEquals(cancel, LedgerRequestHasher.hashCancel(ReferenceType.CHARGE, UUID.randomUUID()),
                "referenceId");
    }

    @Test
    void 값의_경계가_달라지면_이어_붙인_문자열이_같아도_해시가_다르다() {
        // 그냥 이어 붙이면 둘 다 "...1000CHARGE_IN"이 된다 (amount 100 + "0CHARGE_IN" / amount 1000 + "CHARGE_IN").
        // "길이:값"으로 적으므로 "3:100" "10:0CHARGE_IN"과 "4:1000" "9:CHARGE_IN"으로 갈린다
        RegisterTransactionCommand left = withWallet(new RegisterTransactionCommand.Entry(
                OwnerType.USER, WALLET_ID, null, Currency.KRW, Direction.CREDIT, 100L, "0CHARGE_IN"));
        RegisterTransactionCommand right = withWallet(new RegisterTransactionCommand.Entry(
                OwnerType.USER, WALLET_ID, null, Currency.KRW, Direction.CREDIT, 1_000L, "CHARGE_IN"));

        assertNotEquals(LedgerRequestHasher.hash(left), LedgerRequestHasher.hash(right));
        assertEquals(64, LedgerRequestHasher.hash(BASE).length());
    }

    private static RegisterTransactionCommand command(ReferenceType referenceType, UUID referenceId,
                                                      UUID originalTransactionId,
                                                      List<RegisterTransactionCommand.Entry> entries) {
        return new RegisterTransactionCommand(referenceType, referenceId, USER_ID, "설명", originalTransactionId,
                entries);
    }

    /** SOURCE는 그대로 두고 지갑 쪽 항목만 바꾼다 */
    private static RegisterTransactionCommand withWallet(RegisterTransactionCommand.Entry wallet) {
        return command(ReferenceType.CHARGE, REFERENCE_ID, ORIGINAL_ID, List.of(SOURCE, wallet));
    }

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
            case SETTLEMENT_OUT -> "SETTLEMENT_OUT";
            case SETTLEMENT_IN -> "SETTLEMENT_IN";
        };
    }
}
