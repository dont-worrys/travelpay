package com.travelpay.wallet.application.reconciliation;

/**
 * 기간 목록을 끝까지 받지 못함 (조회 실패·응답 이상·페이지 상한 초과).
 * 대사 실행은 FAILED로 끝나고 판정 결과는 저장하지 않는다. 메시지는 실패 사유로 저장되므로 응답 본문을 싣지 않는다.
 */
public class ReconciliationSourceException extends RuntimeException {

    public ReconciliationSourceException(String message) {
        super(message);
    }

    public ReconciliationSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
