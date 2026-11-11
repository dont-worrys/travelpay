package com.travelpay.user.presentation.controller;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.AdminTermsService;
import com.travelpay.user.presentation.dto.AdminTermsListRequest;
import com.travelpay.user.presentation.dto.AdminTermsListResponse;
import com.travelpay.user.presentation.dto.AdminTermsPublishRequest;
import com.travelpay.user.presentation.dto.TermsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 약관 관리 API입니다. ACTIVE·MASTER 확인은 보안 설정의 /api/v1/admin/** 규칙이 먼저 합니다. */
@Tag(name = "약관 관리")
@RestController
@RequiredArgsConstructor
public class AdminTermsController {

    private final AdminTermsService adminTermsService;

    /** 약관 목록 조회: 지난·현재·시행 예정 약관 버전을 모두, 약관 코드 순·최신 시행 순으로 돌려줍니다. */
    @Operation(summary = "약관 버전 목록")
    @GetMapping("/api/v1/admin/terms")
    public ApiResponse<AdminTermsListResponse> list(
            // Swagger 문서에서 검색 조건을 한 묶음 대신 쿼리 입력칸 하나씩으로 보여 줌
            @Valid @ParameterObject @ModelAttribute AdminTermsListRequest request) {
        return ApiResponse.success(AdminTermsListResponse.from(adminTermsService.list(request.toCommand())));
    }

    /** 새 약관 버전 등록: 새 버전을 추가만 하며, 게시한 버전을 고치거나 지우는 API는 두지 않습니다. */
    @Operation(summary = "새 약관 버전 등록")
    @PostMapping("/api/v1/admin/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TermsResponse.TermItem> publish(@CurrentUser AuthenticatedUser admin,
            @Valid @RequestBody AdminTermsPublishRequest body) {
        var published = adminTermsService.publish(admin.userId(), body.toCommand());
        return ApiResponse.success(TermsResponse.TermItem.from(published));
    }
}
