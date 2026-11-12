package com.travelpay.ledger.application.transaction.dto;

import com.travelpay.ledger.domain.transaction.ReferenceType;

import java.util.UUID;

/** 거래 취소 요청. 금액·항목 없이 reference만 받는다 */
public record CancelTransactionCommand(ReferenceType referenceType, UUID referenceId, UUID userId,
                                       String description) {
}
