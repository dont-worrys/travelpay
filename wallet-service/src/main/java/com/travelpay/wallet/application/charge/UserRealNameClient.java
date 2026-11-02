package com.travelpay.wallet.application.charge;

import java.util.UUID;

/**
 * 회원 실명 조회 (충전 예금주 확인용 스냅샷).
 * 정지·탈퇴·없는 회원은 BusinessException(CHARGE_USER_NOT_ACTIVE),
 * 그 밖의 호출 실패는 공통 BusinessException(504·503·502),
 * 2xx 응답 내용 이상은 UserResponseException(500)으로 알린다.
 */
public interface UserRealNameClient {

    String findRealName(UUID userId);
}
