package com.travelpay.payment.transfer.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 송금인은 요청 본문이 아니라 인증된 요청자로 정하므로 필드에 두지 않습니다. */
public record TransferCreateRequest(
        @NotNull(message = "수취인을 입력해 주세요.")
        UUID receiverId,

        @NotNull(message = "송금 금액을 입력해 주세요.")
        @Positive(message = "송금 금액은 0보다 커야 합니다.")
        Long amount,

        @NotBlank(message = "통화를 입력해 주세요.")
        @Pattern(regexp = "^[A-Z]{3}$", message = "통화 코드 형식을 확인해 주세요.")
        String currency,

        @Size(max = 100, message = "메모는 100자 이하로 입력해 주세요.")
        String memo
) {
}
