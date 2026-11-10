package com.travelpay.wallet.application.charge.dto;

import java.util.UUID;

/** 원장 거래 등록 결과. 예외로 알리지 않고 결과 종류로 나눈다 */
public sealed interface LedgerPostResult {

    /** 201 신규 / 200 같은 요청 재요청 */
    record Posted(UUID transactionId) implements LedgerPostResult {
    }

    /** 422 확정 거절. errorCode는 원장 error.code (LEDGER_REJECTED_*) */
    record Rejected(String errorCode) implements LedgerPostResult {
    }

    /** 타임아웃·5xx·연결 실패 */
    record Unknown(String errorClass) implements LedgerPostResult {
    }

    /** 409·400·그 밖의 4xx·응답 형식 이상: 재시도로 풀리지 않음. cause는 last_error에 남길 원인 */
    record Invalid(String cause) implements LedgerPostResult {
    }
}
