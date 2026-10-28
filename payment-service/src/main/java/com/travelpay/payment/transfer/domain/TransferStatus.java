package com.travelpay.payment.transfer.domain;

/**
 * 송금 상태입니다.
 * 1차 MVP는 원장 연동 전이라 실제 자금 이동 없이 접수 상태만 사용합니다.
 * 상태를 추가하면 transfer 테이블의 ck_transfer_status도 새 마이그레이션으로 변경해야 합니다.
 */
public enum TransferStatus {
    REQUESTED
}
