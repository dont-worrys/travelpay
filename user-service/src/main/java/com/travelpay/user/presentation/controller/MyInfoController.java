package com.travelpay.user.presentation.controller;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.MyInfoService;
import com.travelpay.user.presentation.dto.MyInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 회원 본인의 마이페이지 정보를 공통 응답 형식으로 반환합니다.
 * 개인정보 응답의 캐시 금지(no-store)는 Spring Security 기본 보안 헤더가 붙입니다.
 */
@RestController
@RequiredArgsConstructor
public class MyInfoController {

    private final MyInfoService myInfoService;

    @GetMapping("/api/v1/users/me")
    public ApiResponse<MyInfoResponse> getMyInfo(@CurrentUser AuthenticatedUser user) {
        return ApiResponse.success(MyInfoResponse.from(myInfoService.getMyInfo(user.userId())));
    }
}
