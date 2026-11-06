package com.travelpay.wallet.domain.reconciliation;

/**
 * 대사 실행 종류.
 * AUTO: 기간 계산으로 정한 실행 (스케줄러, 기간 생략 수동 실행). 다음 기간 계산의 기준이 된다.
 * MANUAL: 기간을 지정한 수동 실행. 다음 기간 계산에서만 빠지고 나머지는 AUTO와 같다
 */
public enum ReconciliationRunType {
    AUTO,
    MANUAL
}
