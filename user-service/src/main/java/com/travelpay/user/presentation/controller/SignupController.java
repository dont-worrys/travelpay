package com.travelpay.user.presentation.controller;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.SignupService;
import com.travelpay.user.presentation.dto.SignupRequest;
import com.travelpay.user.presentation.dto.SignupResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 일반 회원가입 요청과 응답을 연결합니다. */
@RestController
@RequiredArgsConstructor
public class SignupController {

    private final SignupService signupService;

    @PostMapping("/api/v1/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        var result = signupService.signup(request.toCommand());
        return ApiResponse.success(SignupResponse.from(result));
    }
}
