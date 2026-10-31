package com.travelpay.wallet.infra.client.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** type·status는 모르는 값이 와도 역직렬화가 실패하지 않도록 문자열로 받고 어댑터에서 검증한다 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankTransactionResponse(UUID bankTxId, String requestId, String type, String status,
                                      String failureCode) {
}
