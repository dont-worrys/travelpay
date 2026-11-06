package com.travelpay.wallet.infra.client.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * 은행 거래 조회 응답 (대사·보정용). 출금·환불 응답과 달리 amount를 받는다.
 * type·status·failureCode는 모르는 값이 와도 역직렬화가 실패하지 않도록 문자열로, amount는 누락을 알 수 있도록 Long으로 받는다.
 * failureCode는 FAILED 거래만 있다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankTransactionDetailResponse(UUID bankTxId, String requestId, String type, String status,
                                            String failureCode, Long amount) {

    /** 실패 코드 없는 응답 (테스트·성공 거래) */
    public BankTransactionDetailResponse(UUID bankTxId, String requestId, String type, String status, Long amount) {
        this(bankTxId, requestId, type, status, null, amount);
    }
}
