package com.travelpay.payment.transfer.domain;

/**
 * 송금 상태입니다.
 * 지갑 송금 처리 구현이 없으면 접수(REQUESTED)와 접수 취소(CANCELLED)만 사용합니다.
 * 처리 구현이 있으면 PROCESSING으로 저장한 뒤 처리 결과에 따라 COMPLETED·FAILED로 바꾸며, 결과를 모르면 PROCESSING에 남깁니다.
 * 취소는 REQUESTED에서만 가능합니다.
 * transfer 테이블의 ck_transfer_status는 아직 REQUESTED·CANCELLED만 허용하므로,
 * 실제 지갑 송금 처리를 연동할 때 새 마이그레이션으로 PROCESSING·COMPLETED·FAILED를 추가해야 합니다.
 */
public enum TransferStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
