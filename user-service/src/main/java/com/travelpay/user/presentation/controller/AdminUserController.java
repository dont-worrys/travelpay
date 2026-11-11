package com.travelpay.user.presentation.controller;

import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.AdminUserService;
import com.travelpay.user.presentation.dto.AdminSessionEndResponse;
import com.travelpay.user.presentation.dto.AdminUserSearchRequest;
import com.travelpay.user.presentation.dto.AdminUserSearchResponse;
import com.travelpay.user.presentation.dto.AdminUserStatusRequest;
import com.travelpay.user.presentation.dto.AdminUserStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 회원 관리 API입니다. ACTIVE·MASTER 확인은 보안 설정의 /api/v1/admin/** 규칙이 먼저 합니다. */
@Tag(name = "회원 관리")
@RestController
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /** 회원 검색: 조건에 맞는 회원을 가입 최신순으로 찾고, 개인정보는 가려서 돌려줍니다. */
    @Operation(summary = "회원 검색")
    @GetMapping("/api/v1/admin/users")
    public ApiResponse<AdminUserSearchResponse> search(
            // Swagger 문서에서 검색 조건을 한 묶음 대신 쿼리 입력칸 하나씩으로 보여 줌
            @Valid @ParameterObject @ModelAttribute AdminUserSearchRequest request) {
        return ApiResponse.success(AdminUserSearchResponse.from(adminUserService.search(request.toCommand())));
    }

    /** 회원 정지·해제: 대상 회원을 정지하거나 정지를 풉니다. */
    @Operation(summary = "회원 정지·해제")
    @PatchMapping("/api/v1/admin/users/{userId}/status")
    public ApiResponse<AdminUserStatusResponse> changeStatus(@CurrentUser AuthenticatedUser admin,
            @PathVariable UUID userId, @Valid @RequestBody AdminUserStatusRequest body) {
        var status = adminUserService.changeStatus(admin.userId(), userId, body.toStatus());
        return ApiResponse.success(new AdminUserStatusResponse(userId, status));
    }

    /** 회원 세션 종료: 대상 회원의 기존 세션을 모두 끝냅니다. */
    @Operation(summary = "회원 세션 종료")
    @DeleteMapping("/api/v1/admin/users/{userId}/sessions")
    public ApiResponse<AdminSessionEndResponse> endSessions(@CurrentUser AuthenticatedUser admin,
            @PathVariable UUID userId) {
        adminUserService.endSessions(admin.userId(), userId);
        return ApiResponse.success(AdminSessionEndResponse.of(userId));
    }
}
