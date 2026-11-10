package com.travelpay.user.application.service;

import java.util.UUID;

/**
 * 송금 수취인 실명 조회의 허용 횟수를 요청자 회원 ID별로 확인하고 사용합니다.
 * 조회 대상과 결과에 관계없이 허용한 호출을 집계하며, 저장소 오류는 예외로 전달합니다.
 */
public interface RecipientLookupLimiter {

    /** 한 번의 조회를 허용하고 집계하면 true, 한도를 소진했으면 false를 반환합니다. */
    boolean tryAcquire(UUID requesterId);
}
