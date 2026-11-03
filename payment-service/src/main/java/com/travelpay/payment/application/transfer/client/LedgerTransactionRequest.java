package com.travelpay.payment.application.transfer.client;

import java.util.List;
import java.util.UUID;

import com.travelpay.payment.domain.transfer.Transfer;


/**
 * 송금의 원장 거래 등록 요청입니다. reference(TRANSFER + 송금 ID)가 원장의 중복 처리 방지 기준이며,
 * 재전송도 저장된 송금 값으로 만들어 처음 요청과 같은 내용을 보냅니다.
 * 송금인 지갑 DEBIT(TRANSFER_OUT)과 수취인 지갑 CREDIT(TRANSFER_IN)을 한 거래로 보내 원장이 함께 반영하거나 함께 거절합니다.
 * memo는 원장 description에 보내지 않습니다.
 * 사용자 계정은 walletId + currency로 지정합니다. userId는 거래를 발생시킨 사용자 기록용 선택값으로 송금인 ID를 보내며,
 * 원장은 계정 조회에 사용하지 않습니다(원장 담당자 확인, 2026-11-02).
 * 미확정: entryType 값(TRANSFER_OUT·TRANSFER_IN, 원장 DB CHECK 미추가).
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
