package com.travelpay.wallet.application.charge;

import java.util.UUID;

/**
 * 회원 충전용 신원(funding-identity) 조회. 충전 예금주 확인용 실명 스냅샷을 돌려준다.
 * - 정지·탈퇴·없는 회원: BusinessException(CHARGE_USER_NOT_ACTIVE, 409)
 * - 호출 실패와 2xx 응답 해석 실패: 공통 BusinessException(504·503·502)
 * - 2xx 응답을 읽었으나 내용 이상(userId 불일치, 실명 없음·길이 초과 등): UserResponseException(500)
 */
public interface UserFundingIdentityClient {

    String findRealName(UUID userId);
}
