package com.travelpay.wallet.infra.client.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 은행의 공통 응답 형식. 형식 검증은 어댑터가 하므로 여기서는 받기만 한다 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankApiResponse<T>(boolean success, T data) {
}
