package com.travelpay.wallet.application.charge.dto;

import java.util.UUID;

/** 은행 출금·환불 호출 결과. 예외로 알리지 않고 결과 종류로 나눈다 */
public sealed interface BankResult {

    /** status SUCCEEDED */
    record Succeeded(UUID bankTxId) implements BankResult {
    }

    /** 200 + status FAILED. failureCode는 은행이 보낸 값 그대로 (없으면 null) */
    record Failed(String failureCode) implements BankResult {
    }

    /** 타임아웃·5xx·연결 실패: 같은 requestId로 다시 보내면 결과를 알 수 있음 */
    record Unknown(String errorClass) implements BankResult {
    }

    /** 409·400·그 밖의 4xx·응답 형식 이상: 재시도로 풀리지 않음. cause는 last_error에 남길 원인 */
    record Invalid(String cause) implements BankResult {
    }
}
