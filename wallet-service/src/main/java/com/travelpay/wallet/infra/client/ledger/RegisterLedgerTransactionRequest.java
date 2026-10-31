package com.travelpay.wallet.infra.client.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/** 원장 거래 등록 요청. 선택 값(null)은 보내지 않는다 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterLedgerTransactionRequest(String referenceType, UUID referenceId, UUID userId,
                                               String description, UUID originalTransactionId,
                                               List<Entry> entries) {

    private static final String CHARGE = "CHARGE";
    private static final String KRW = "KRW";

    /** 충전: 충전 원천(SYSTEM CHARGE_SOURCE) 차변 → 회원 지갑 대변, 원화만 */
    public static RegisterLedgerTransactionRequest charge(UUID chargeId, UUID userId, UUID walletId, long amount) {
        return new RegisterLedgerTransactionRequest(CHARGE, chargeId, userId, null, null, List.of(
                new Entry("SYSTEM", null, "CHARGE_SOURCE", KRW, "DEBIT", amount, "CHARGE_OUT"),
                new Entry("USER", walletId, null, KRW, "CREDIT", amount, "CHARGE_IN")));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String ownerType, UUID walletId, String accountType, String currency, String direction,
                        long amount, String entryType) {
    }
}
