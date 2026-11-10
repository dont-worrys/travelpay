package com.travelpay.payment.presentation.merchant;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.application.merchant.SettlementReviewService;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import com.travelpay.payment.presentation.merchant.dto.SettlementManualReviewResponse;
import com.travelpay.payment.presentation.merchant.dto.SettlementResolutionResponse;
import com.travelpay.payment.presentation.merchant.dto.SettlementResolveResponse;
import com.travelpay.payment.presentation.merchant.dto.SettlementReviewDetailResponse;
import com.travelpay.payment.presentation.payment.dto.ManualReviewResolveRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

// MANUAL_REVIEW 정산 운영자 처리 API (MASTER 전용). 결제·환불 운영자 처리와 같은 방식이고 결과는 원장 조회로 정한다
@Tag(name = "정산 운영자 처리", description = "MASTER의 MANUAL_REVIEW 정산 처리와 처리 기록")
@RestController
@RequestMapping("/api/v1/admin/payments/settlements")
@RequiredArgsConstructor
public class AdminSettlementController {

    private final SettlementReviewService settlementReviewService;

    @Operation(summary = "MANUAL_REVIEW 정산 목록")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "오래된 순 페이지. `reviewReason`으로 거름")
    })
    @GetMapping("/manual-reviews")
    public ResponseEntity<ApiResponse<PageResponse<SettlementManualReviewResponse>>> getManualReviews(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) SettlementReviewReason reviewReason,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        requireMaster(user);
        PageResponse<SettlementManualReviewResponse> response = PageResponse.from(
                settlementReviewService.getManualReviews(reviewReason, page, size),
                SettlementManualReviewResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 운영자 처리 기록 목록
    @Operation(summary = "정산 운영자 처리 기록 목록")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "최신순 페이지. `actorId`·`settlementId`로 거름")
    })
    @GetMapping("/resolutions")
    public ResponseEntity<ApiResponse<PageResponse<SettlementResolutionResponse>>> getResolutions(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID settlementId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        requireMaster(user);
        PageResponse<SettlementResolutionResponse> response = PageResponse.from(
                settlementReviewService.getResolutions(actorId, settlementId, page, size),
                SettlementResolutionResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 정산 단건 (원장 조회·예상 결과)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "정산 정보, 포함된 결제 수, 원장 조회 결과(`ledger`)와 처리하면 될 결과(`expected`). 원장 조회 실패면 `ledgerLookupFailed: true`")
    })
    @GetMapping("/{settlementId}")
    public ResponseEntity<ApiResponse<SettlementReviewDetailResponse>> getReview(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID settlementId) {
        requireMaster(user);
        SettlementReviewDetailResponse response = SettlementReviewDetailResponse.from(
                settlementReviewService.getReview(user.userId(), settlementId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 정산 처리")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "처리 결과. `PAID`(원장 반영 확인) 또는 `FAILED`(원장 거절·기록 없음, 포함됐던 결제는 다시 정산 대상)")
    })
    @PostMapping("/{settlementId}/resolve")
    public ResponseEntity<ApiResponse<SettlementResolveResponse>> resolve(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID settlementId,
            @RequestBody(required = false) @Valid ManualReviewResolveRequest request) {
        requireMaster(user);
        SettlementResolveResponse response = SettlementResolveResponse.from(
                settlementReviewService.resolve(user.userId(), settlementId, ManualReviewResolveRequest.memoOf(request)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
