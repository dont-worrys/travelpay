package com.travelpay.payment.transfer.domain;

/**
 * 송금 상태입니다.
 * 새 송금은 PROCESSING으로 저장한 뒤 원장 거래 등록 결과에 따라 COMPLETED·FAILED로 바꾸며, 결과를 모르면 PROCESSING에 남겨
 * 복구 스케줄러가 다시 요청합니다. 원장이 요청을 받아들이지 않거나 결과 모름이 한도에 이르면 자동 처리를 멈추고 MANUAL_REVIEW로 둡니다.
 * REQUESTED(원장 연동 전 접수)와 CANCELLED(그 접수의 취소)는 더 이상 만들지 않으며, 예전에 저장된 행을 읽으려고 남겨 둡니다.
 */
public enum TransferStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    MANUAL_REVIEW
}
