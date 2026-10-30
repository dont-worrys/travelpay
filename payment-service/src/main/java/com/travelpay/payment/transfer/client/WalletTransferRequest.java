package com.travelpay.payment.transfer.client;

import java.util.UUID;

import com.travelpay.payment.transfer.domain.Transfer;

/** transferId가 중복 처리 방지 기준(reference)입니다. 재전송도 저장된 송금 값으로 만들어 처음 요청과 같은 내용을 보냅니다. */
public record WalletTransferRequest(
        UUID transferId,
        UUID sourceWalletId,
        UUID targetWalletId,
        String currency,
        long amount
) {

    public static WalletTransferRequest of(Transfer transfer) {
        return new WalletTransferRequest(
                transfer.getId(),
                transfer.getSourceWalletId(),
                transfer.getTargetWalletId(),
                transfer.getCurrency(),
                transfer.getAmount()
        );
    }
}
