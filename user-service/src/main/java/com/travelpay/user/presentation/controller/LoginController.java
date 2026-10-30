package com.travelpay.user.presentation.controller;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.LoginService;
import com.travelpay.user.common.security.session.SessionLoginHandler;
import com.travelpay.user.presentation.dto.LoginRequest;
import com.travelpay.user.presentation.dto.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 일반 로그인 HTTP 요청을 처리합니다. */
@RestController
@RequiredArgsConstructor
public class LoginController {

    private final LoginService loginService;
    private final SessionLoginHandler sessionLoginHandler;

    @PostMapping("/api/v1/auth/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        // Gateway를 거치면 Gateway 주소가 되므로, Gateway 연결 때 실제 클라이언트 IP를 전달받도록 바꿔야 합니다.
        var result = loginService.login(body.toCommand(request.getRemoteAddr()));
        sessionLoginHandler.onLoginSuccess(result, request, response);
        return ApiResponse.success(LoginResponse.from(result));
    }
}
