package com.travelpay.wallet.application.charge.dto;

import java.util.UUID;

/** 관리자용 충전 조회 결과. 회원 결과에 회원 ID와 현재 단계 재시도 횟수를 더한다 (MANUAL_REVIEW 조사용) */
public record AdminChargeResult(ChargeResult charge, UUID userId, int attemptCount) {
}
