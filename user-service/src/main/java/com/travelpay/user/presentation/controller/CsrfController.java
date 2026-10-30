package com.travelpay.user.presentation.controller;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.presentation.dto.CsrfTokenResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 변경 요청에 사용할 CSRF 헤더 이름과 토큰을 전달합니다. */
@RestController
public class CsrfController {

    @GetMapping("/api/v1/auth/csrf")
    public ApiResponse<CsrfTokenResponse> getCsrfToken(CsrfToken csrfToken) {
        var data = new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
        return ApiResponse.success(data);
    }
}
