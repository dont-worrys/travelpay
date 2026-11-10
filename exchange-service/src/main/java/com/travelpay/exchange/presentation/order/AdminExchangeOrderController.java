package com.travelpay.exchange.presentation.order;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.exchange.application.order.ExchangeAdminService;
import com.travelpay.exchange.application.order.ExchangeResolutionCondition;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/exchanges")
@RequiredArgsConstructor
public class AdminExchangeOrderController {

    private final ExchangeAdminService adminService;

    /**
     * MANUAL_REVIEW 주문 목록을 오래된 순으로 조회합니다.
     */
    @GetMapping("/manual-reviews")
    public ApiResponse<PageResponse<AdminExchangeManualReviewResponse>> list(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) ManualReviewReason reviewReason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMaster(user);
        return ApiResponse.success(PageResponse.from(
                adminService.getManualReviews(user.userId(), reviewReason, page, size),
                AdminExchangeManualReviewResponse::from
        ));
    }

    /**
     * 관리자 처리 이력을 처리 시각 최신순으로 조회합니다. 조건은 모두 선택이며, 기간은 from 포함·to 제외입니다.
     * 경로가 /{orderId}보다 구체적이라 Spring이 이 메서드를 먼저 고릅니다(주문 ID로 해석하지 않음).
     */
    @GetMapping("/resolutions")
    public ApiResponse<PageResponse<AdminExchangeResolutionResponse>> resolutions(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMaster(user);
        return ApiResponse.success(PageResponse.from(
                adminService.getResolutions(user.userId(),
                        new ExchangeResolutionCondition(actorId, orderId, from, to), page, size),
                AdminExchangeResolutionResponse::from
        ));
    }

    /**
     * MANUAL_REVIEW 주문 단건 조회 (원장 조회 결과 및 예상 처리 결과 포함).
     */
    @GetMapping("/{orderId}")
    public ApiResponse<AdminExchangeReviewDetailResponse> get(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID orderId) {
        requireMaster(user);
        return ApiResponse.success(AdminExchangeReviewDetailResponse.from(
                adminService.getManualReview(user.userId(), orderId)
        ));
    }

    /**
     * MANUAL_REVIEW 주문을 원장 조회 결과에 따라 확정 처리합니다.
     */
    @PostMapping("/{orderId}/resolve")
    public ResponseEntity<ApiResponse<AdminExchangeResolveResponse>> resolve(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID orderId,
            @RequestBody(required = false) @Valid AdminExchangeResolveRequest request) {
        requireMaster(user);
        AdminExchangeResolveResponse response = AdminExchangeResolveResponse.from(
                adminService.resolveManualReview(user.userId(), orderId, AdminExchangeResolveRequest.memoOf(request))
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
