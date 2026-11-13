package com.travelpay.payment.domain.payment;

/** MANUAL_REVIEW를 처리한 주체 */
public enum ReviewActorType {
    /** 운영자(MASTER)가 처리 API로 처리. actorId는 운영자 회원 ID */
    OPERATOR,
    /** 스케줄러가 원장 결과로 자동 처리. actorId 없음 */
    SYSTEM
}
