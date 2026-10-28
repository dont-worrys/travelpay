package com.travelpay.wallet.domain.charge;

/** 화면에 보여주는 충전 상태 (내부 상태 8개를 5개로 묶음) */
public enum ChargeDisplayStatus {
    PROCESSING, // 처리 중
    COMPLETED,  // 완료
    FAILED,     // 실패
    REFUNDED,   // 환불 완료
    REVIEWING   // 확인 중
}
