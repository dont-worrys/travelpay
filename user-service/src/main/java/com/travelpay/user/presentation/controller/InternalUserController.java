package com.travelpay.user.presentation.controller;

import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUserHeaderParser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.UserRealNameService;
import com.travelpay.user.presentation.dto.UserRealNameResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다른 업무 서비스가 호출하는 내부 회원 이름 조회 API입니다.
 * 요청자 회원 헤더를 검사하며, 호출 서비스의 신뢰는 내부망 격리를 전제로 합니다.
 */
@RestController
@RequiredArgsConstructor
public class InternalUserController {

    private final UserRealNameService userRealNameService;

    /** 송금 수취인 실명 조회: 요청자 회원 헤더를 검사한 뒤 경로의 수취인을 조회합니다. */
    @GetMapping("/internal/v1/users/{userId}/real-name")
    public ApiResponse<UserRealNameResponse> getRecipient(
            @PathVariable("userId") UUID recipientId, HttpServletRequest request) {
        var requester = requireRequester(request);
        return ApiResponse.success(UserRealNameResponse.from(
                userRealNameService.getRecipient(requester.userId(), recipientId)));
    }

    /** 충전 예금주(본인 실명) 조회: 헤더의 요청자 ID와 경로의 대상 ID를 본인 확인에 전달합니다. */
    @GetMapping("/internal/v1/users/{userId}/funding-identity")
    public ApiResponse<UserRealNameResponse> getFundingIdentity(
            @PathVariable UUID userId, HttpServletRequest request) {
        var requester = requireRequester(request);
        return ApiResponse.success(UserRealNameResponse.from(
                userRealNameService.getFundingIdentity(requester.userId(), userId)));
    }

    private static AuthenticatedUser requireRequester(HttpServletRequest request) {
        return CurrentUserHeaderParser.parse(request)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
    }
}
