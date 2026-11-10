package com.travelpay.user.presentation.controller;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.presentation.dto.SessionVerificationResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 세션 확인 API: Gateway가 로그인이 필요한 요청을 업무 서비스로 넘기기 전에 부릅니다. */
@RestController
public class InternalSessionController {

    // 헤더 형식·CSRF·세션·회원 상태는 보안 필터가 먼저 확인하므로 여기엔 통과한 요청만 옴
    @PostMapping("/internal/v1/auth/session")
    public ApiResponse<SessionVerificationResponse> verifySession(@CurrentUser AuthenticatedUser user) {
        return ApiResponse.success(SessionVerificationResponse.from(user));
    }
}
