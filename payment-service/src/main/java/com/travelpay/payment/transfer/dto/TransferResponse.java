package com.travelpay.payment.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferDirection;
import com.travelpay.payment.transfer.domain.TransferStatus;

/** 지갑 ID·멱등성 키 등 내부 식별 정보는 응답에 포함하지 않습니다. */
public record TransferResponse(
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
    public static TransferResponse of(Transfer transfer, UUID viewerId) {
        TransferDirection direction = transfer.getSenderId().equals(viewerId)
                ? TransferDirection.SENT : TransferDirection.RECEIVED;
        return new TransferResponse(
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
