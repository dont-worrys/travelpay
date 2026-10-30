package com.travelpay.bank.presentation.transaction.dto;

import com.travelpay.bank.application.transaction.dto.BankRefundCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record BankRefundRequest(
        @NotBlank(message = "requestId를 입력해 주세요.")
        @Size(max = 100, message = "requestId는 100자 이하여야 합니다.")
        String requestId,

        @NotNull(message = "원거래 ID를 입력해 주세요.")
        UUID originalTxId,

        @NotNull(message = "금액을 입력해 주세요.")
        @Positive(message = "금액은 0보다 커야 합니다.")
        Long amount
) {

    public BankRefundCommand toCommand() {
        return new BankRefundCommand(requestId, originalTxId, amount);
    }
}
