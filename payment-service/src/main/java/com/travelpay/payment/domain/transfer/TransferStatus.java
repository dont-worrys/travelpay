package com.travelpay.payment.domain.transfer;

/**
 * 송금 상태입니다.
 * 원장 거래 등록 구현이 없으면 접수(REQUESTED)와 접수 취소(CANCELLED)만 사용합니다.
 * 구현이 있으면 PROCESSING으로 저장한 뒤 원장 거래 등록 결과에 따라 COMPLETED·FAILED로 바꾸며, 결과를 모르면 PROCESSING에 남겨
 * 복구 스케줄러가 다시 요청합니다. 원장이 요청을 받아들이지 않거나 결과 모름이 한도에 이르면 자동 처리를 멈추고 MANUAL_REVIEW로 둡니다.
 * 취소는 REQUESTED에서만 가능합니다.
 */
public enum TransferStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    MANUAL_REVIEW
}
