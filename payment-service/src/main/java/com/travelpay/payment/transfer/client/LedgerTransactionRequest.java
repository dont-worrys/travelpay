package com.travelpay.payment.transfer.client;

import java.util.List;
import java.util.UUID;

import com.travelpay.payment.transfer.domain.Transfer;

/**
 * 송금의 원장 거래 등록 요청입니다. reference(TRANSFER + 송금 ID)가 원장의 중복 처리 방지 기준이며,
 * 재전송도 저장된 송금 값으로 만들어 처음 요청과 같은 내용을 보냅니다.
 * 송금인 지갑 DEBIT(TRANSFER_OUT)과 수취인 지갑 CREDIT(TRANSFER_IN)을 한 거래로 보내 원장이 함께 반영하거나 함께 거절합니다.
 * memo는 원장 description에 보내지 않습니다.
 * 미확정(현재 ledger-service 코드 기준, 원장 연동 시 재확인): 사용자 계정을 walletId + currency로 지정하는 방식,
 * entryType 값(TRANSFER_OUT·TRANSFER_IN, 원장 DB CHECK 미추가), userId 전송 여부.
 */
public record LedgerTransactionRequest(
        String referenceType,
        UUID referenceId,
        UUID userId,
        List<Entry> entries
) {

    static final String REFERENCE_TYPE = "TRANSFER";

    public static LedgerTransactionRequest of(Transfer transfer) {
        return new LedgerTransactionRequest(
                REFERENCE_TYPE,
                transfer.getId(),
                transfer.getSenderId(),
                List.of(
                        Entry.user(transfer.getSourceWalletId(), transfer.getCurrency(), "DEBIT",
                                transfer.getAmount(), "TRANSFER_OUT"),
                        Entry.user(transfer.getTargetWalletId(), transfer.getCurrency(), "CREDIT",
                                transfer.getAmount(), "TRANSFER_IN")
                )
        );
    }

    /** 사용자 계정은 지갑 ID와 통화로 지정합니다. */
    public record Entry(
            String ownerType,
            UUID walletId,
            String currency,
            String direction,
            long amount,
            String entryType
    ) {

        static Entry user(UUID walletId, String currency, String direction, long amount, String entryType) {
            return new Entry("USER", walletId, currency, direction, amount, entryType);
        }
    }
}
