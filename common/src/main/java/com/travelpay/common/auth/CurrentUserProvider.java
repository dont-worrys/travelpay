package com.travelpay.common.auth;

import java.util.Optional;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;

/** 검증된 현재 요청자 정보를 얻는 계약이며, 컨텍스트 접근은 실행 환경별 구현체가 담당합니다. */
public interface CurrentUserProvider {

    /**
     * 현재 요청자 정보가 없으면 empty를 반환합니다.
     * 조회 중 발생한 오류는 empty로 바꾸지 않고 호출자에게 전달해야 합니다.
     */
    Optional<AuthenticatedUser> findCurrentUser();

    /** 인증이 필수인 흐름에서 사용하며, 요청자 정보가 없을 때만 미인증 오류를 발생시킵니다. */
    default AuthenticatedUser requireCurrentUser() {
        return findCurrentUser()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
    }
}
