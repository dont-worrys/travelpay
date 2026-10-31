package com.travelpay.user.presentation.controller;

import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.UserRealNameService;
import com.travelpay.user.presentation.dto.UserRealNameResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다른 업무 서비스가 호출하는 내부 회원 이름 조회 API입니다.
 * 회원 세션 없이 호출하며, 접근 규칙은 SecurityConfiguration에 있습니다.
 */
@RestController
@RequiredArgsConstructor
public class InternalUserController {

    private final UserRealNameService userRealNameService;

    /** 명세 I02: 송금 수취인 확인용입니다. */
    @GetMapping("/internal/v1/users/{userId}/real-name")
    public ApiResponse<UserRealNameResponse> getRecipient(@PathVariable UUID userId) {
        return ApiResponse.success(UserRealNameResponse.from(userRealNameService.getRecipient(userId)));
    }

    /** 명세 I03: 충전 예금주 확인용입니다. */
    @GetMapping("/internal/v1/users/{userId}/funding-identity")
    public ApiResponse<UserRealNameResponse> getFundingIdentity(@PathVariable UUID userId) {
        return ApiResponse.success(UserRealNameResponse.from(userRealNameService.getFundingIdentity(userId)));
    }
}
