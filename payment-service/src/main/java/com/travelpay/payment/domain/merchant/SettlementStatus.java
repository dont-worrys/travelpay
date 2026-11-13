package com.travelpay.payment.domain.merchant;

public enum SettlementStatus {
    REQUESTED,  // 원장 처리 결과 미확정. 복구 스케줄러가 다시 요청
    PAID,       // 원장 반영 완료. 가맹점 지갑 → 주인 지갑
    FAILED,     // 원장 거절. 정산 항목을 지워 포함됐던 결제는 다음 요청에서 다시 대상이 됨
    MANUAL_REVIEW  // 자동 재시도 중단(결과 모름 5회·원장이 받아들이지 않음). 항목은 유지, 운영자가 원장 조회로 PAID·FAILED 확정
}
