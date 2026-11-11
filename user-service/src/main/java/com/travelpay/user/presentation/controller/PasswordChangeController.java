package com.travelpay.user.presentation.controller;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.user.application.service.PasswordChangeService;
import com.travelpay.user.common.security.session.SessionPasswordChangeHandler;
import com.travelpay.user.presentation.dto.PasswordChangeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 회원의 비밀번호를 변경하고 현재 세션을 갱신합니다. */
@Tag(name = "회원")
@RestController
@RequiredArgsConstructor
public class PasswordChangeController {

    private final PasswordChangeService passwordChangeService;
    private final SessionPasswordChangeHandler sessionPasswordChangeHandler;

    @Operation(summary = "비밀번호 변경")
    @PatchMapping("/api/v1/users/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@CurrentUser AuthenticatedUser user,
            @Valid @RequestBody PasswordChangeRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        passwordChangeService.changePassword(user.userId(), body.toCommand(request.getRemoteAddr()));
        // DB 커밋이 끝난 뒤에만 현재 세션 ID와 CSRF를 갱신합니다.
        sessionPasswordChangeHandler.onPasswordChangeSuccess(request, response);
    }
}
