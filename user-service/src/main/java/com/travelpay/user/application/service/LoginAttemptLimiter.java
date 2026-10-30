package com.travelpay.user.application.service;

/**
 * 로그인 실패 횟수를 이메일+IP 단위와 이메일 단위로 세고, 한도에 닿은 단위를 일정 시간 막습니다.
 * 횟수 저장소에 접근할 수 없으면 DataAccessException을 던집니다.
 */
public interface LoginAttemptLimiter {

    /** 막힌 단위가 없고 실패 횟수와 진행 중 시도의 합이 한도보다 작으면, 이번 시도를 진행 중으로 세고 true를 반환합니다. */
    boolean tryStart(String email, String clientIp);

    /** 진행 중이던 시도를 실패로 확정합니다. 실패 횟수가 한도에 닿은 단위는 차단 시간 동안 막힙니다. */
    void recordFailure(String email, String clientIp);

    /** 시작한 시도를 성공으로 끝냅니다. 이메일+IP 실패 횟수는 초기화하고, 이메일 실패 횟수는 유지합니다. */
    void recordSuccess(String email, String clientIp);

    /** 실패로 세지 않는 결과로 끝난 시도를 진행 중 시도에서만 뺍니다. */
    void cancel(String email, String clientIp);
}
