package com.travelpay.wallet.presentation.charge.dto;

import com.travelpay.wallet.domain.Currency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 검증 안내 문구에는 입력 원문을 넣지 않는다. toString에 계좌번호를 남기지 않는다 */
public record ChargeCreateRequest(
        @NotNull(message = "충전 금액을 입력해 주세요.")
        @Positive(message = "충전 금액은 0보다 커야 합니다.")
        Long amount,

        @NotNull(message = "통화를 입력해 주세요.")
        Currency currency,

        /** 금융결제원 표준 은행 코드 3자리 */
        @NotNull(message = "은행 코드를 입력해 주세요.")
        @Pattern(regexp = "^[0-9]{3}$", message = "은행 코드는 숫자 3자리여야 합니다.")
        String bankCode,

        @NotNull(message = "계좌번호를 입력해 주세요.")
        @Pattern(regexp = "^[0-9]{10,20}$", message = "계좌번호는 숫자 10~20자리여야 합니다.")
        String accountNumber
) {

    @Override
    public String toString() {
        return "ChargeCreateRequest[amount=" + amount + ", currency=" + currency + ", bankCode=" + bankCode + "]";
    }
}
