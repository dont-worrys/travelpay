package com.travelpay.user.presentation.controller;

import com.travelpay.user.common.security.session.SessionLogoutHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 현재 로그인 세션을 종료합니다. 회원 상태와 관계없이 자기 세션은 종료할 수 있습니다. */
@RestController
@RequiredArgsConstructor
public class LogoutController {

    private final SessionLogoutHandler sessionLogoutHandler;

    /** 명세 B04: 성공은 본문 없는 204입니다. */
    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessionLogoutHandler.logout(request, response);
    }
}
