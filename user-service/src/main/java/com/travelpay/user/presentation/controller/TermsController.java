package com.travelpay.user.presentation.controller;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.TermsService;
import com.travelpay.user.presentation.dto.TermsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현재 시행 중인 약관을 공통 응답 형식으로 반환합니다. */
@Tag(name = "약관")
@RestController
@RequiredArgsConstructor
public class TermsController {

    private final TermsService termsService;

    @Operation(summary = "현재 시행 약관 조회")
    @GetMapping("/api/v1/terms")
    public ApiResponse<TermsResponse> getCurrentTerms() {
        return ApiResponse.success(
                TermsResponse.from(termsService.getCurrentTerms()));
    }
}
