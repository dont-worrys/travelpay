package com.travelpay.payment.application.payment.dto;

/**
 * 복구 배치 한 번의 결과. fetched는 이번에 가져온 재시도 대상 수(배치 크기와 같으면 더 남아 있을 수 있음),
 * ledgerUnavailable은 결과 모름이 연달아 나와 원장 장애로 보고 중단했는지입니다.
 */
public record RecoveryBatchResult(int fetched, boolean ledgerUnavailable) {
}
