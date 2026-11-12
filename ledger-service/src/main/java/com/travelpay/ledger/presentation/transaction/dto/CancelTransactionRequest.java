package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.CancelTransactionCommand;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 거래 취소 요청. 금액·항목은 받지 않는다 */
public record CancelTransactionRequest(
        @NotNull(message = "참조 유형을 입력해 주세요.") ReferenceType referenceType,
        @NotNull(message = "참조 ID를 입력해 주세요.") UUID referenceId,
        UUID userId,
        @Size(max = 255, message = "설명은 255자 이하로 입력해 주세요.") String description) {

    public CancelTransactionCommand toCommand() {
        return new CancelTransactionCommand(referenceType, referenceId, userId, description);
    }
}
