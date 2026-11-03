package com.travelpay.payment.application.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.payment.domain.transfer.Transfer;
import com.travelpay.payment.domain.transfer.TransferDirection;
import com.travelpay.payment.domain.transfer.TransferStatus;

/** 요청자 기준 송금 정보입니다. 지갑 ID·멱등성 키 등 내부 식별 정보는 담지 않습니다. */
public record TransferResult(
        UUID transferId,
        TransferDirection direction,
        UUID senderId,
        UUID receiverId,
        long amount,
        String currency,
        TransferStatus status,
        String memo,
        Instant createdAt
) {

    /** viewerId는 송금 당사자여야 하며, 당사자 확인은 호출자가 먼저 수행합니다. */
    public static TransferResult of(Transfer transfer, UUID viewerId) {
        TransferDirection direction = transfer.getSenderId().equals(viewerId)
                ? TransferDirection.SENT : TransferDirection.RECEIVED;
        return new TransferResult(
                transfer.getId(),
                direction,
                transfer.getSenderId(),
                transfer.getReceiverId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getMemo(),
                transfer.getCreatedAt()
        );
    }
}
