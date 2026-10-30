package com.travelpay.bank.presentation.transaction.dto;

import com.travelpay.bank.application.transaction.dto.BankWithdrawalCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BankWithdrawalRequest(
        @NotBlank(message = "requestId를 입력해 주세요.")
        @Size(max = 100, message = "requestId는 100자 이하여야 합니다.")
        String requestId,

        @NotNull(message = "은행 코드를 입력해 주세요.")
        @Pattern(regexp = "\\d{3}", message = "은행 코드는 숫자 3자리여야 합니다.")
        String bankCode,

        @NotNull(message = "계좌번호를 입력해 주세요.")
        @Pattern(regexp = "\\d{10,20}", message = "계좌번호는 숫자 10~20자리여야 합니다.")
        String accountNumber,

        @NotBlank(message = "예금주를 입력해 주세요.")
        @Size(max = 100, message = "예금주는 100자 이하여야 합니다.")
        String holderName,

        @NotNull(message = "금액을 입력해 주세요.")
        @Positive(message = "금액은 0보다 커야 합니다.")
        Long amount
) {

    public BankWithdrawalCommand toCommand() {
        return new BankWithdrawalCommand(requestId, bankCode, accountNumber, holderName, amount);
    }
}
