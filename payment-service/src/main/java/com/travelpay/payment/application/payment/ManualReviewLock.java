package com.travelpay.payment.application.payment;

/**
 * MANUAL_REVIEW 자동 처리를 서버 여러 대 중 한 곳에서만 실행하게 하는 잠금입니다.
 * 차례마다 잠금을 시도해 잡은 서버만 그 차례를 처리하고, 끝나면 풉니다.
 * 자동 처리 대상(MANUAL_REVIEW)은 선점 표시를 남길 곳이 없어(updated_at은 MANUAL_REVIEW가 된 시각) 대상 단위로 나누지 않고 실행 자체를 하나로 묶습니다.
 */
public interface ManualReviewLock {

    /** 잠금을 잡으면 task를 실행하고 true, 다른 서버가 실행 중이면 실행하지 않고 false */
    boolean runExclusively(Runnable task);
}
