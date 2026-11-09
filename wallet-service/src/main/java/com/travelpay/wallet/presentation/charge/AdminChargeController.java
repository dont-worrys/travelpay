package com.travelpay.wallet.presentation.charge;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.charge.ChargeService;
import com.travelpay.wallet.application.resolution.ChargeResolutionService;
import com.travelpay.wallet.application.resolution.dto.ChargeResolutionResult;
import com.travelpay.wallet.application.resolution.dto.ChargeReviewResult;
import com.travelpay.wallet.application.resolution.dto.ResolveChargeCommand;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.presentation.charge.dto.AdminChargeResponse;
import com.travelpay.wallet.presentation.charge.dto.ChargeResolveRequest;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** 관리자(MASTER) 충전 조회·보정. 목록은 최신순 고정, 기간은 createdAt 기준 [from, to). 마이크로초보다 세밀한 시각은 다음 마이크로초로 올려 조회 */
@RestController
@RequestMapping("/api/v1/admin/charges")
@RequiredArgsConstructor
public class AdminChargeController {

    private final ChargeService chargeService;
    private final ChargeResolutionService resolutionService;

    @GetMapping
    public ApiResponse<PageResponse<AdminChargeResponse>> listCharges(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) ChargeStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMaster(user);
        return ApiResponse.success(PageResponse.from(
                chargeService.listCharges(userId, status, from, to, page, size), AdminChargeResponse::from));
    }

    /** MANUAL_REVIEW 충전의 지금 은행·원장 사실과 가능한 조치. 아무것도 바꾸지 않으며 실행 허가가 아니다 */
    @GetMapping("/{chargeId}/review")
    public ApiResponse<ChargeReviewResult> review(@CurrentUser AuthenticatedUser user, @PathVariable UUID chargeId) {
        requireMaster(user);
        return ApiResponse.success(resolutionService.review(chargeId));
    }

    /** MANUAL_REVIEW 충전 보정. 실행 순간 은행·원장을 다시 조회해 판단한다. Idempotency-Key(UUID) 필수 */
    @PostMapping("/{chargeId}/resolve")
    public ApiResponse<ChargeResolutionResult> resolve(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID chargeId,
            @Parameter(required = true,
                    description = "중복 보정 방지 키. 표준 UUID 표기이며, 보정마다 새 값을 씁니다. 없거나 형식이 다르면 400입니다.",
                    example = "6f1c2b9e-3d4a-4e5f-8a7b-1c2d3e4f5a6b")
            @RequestHeader(name = ChargeController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ChargeResolveRequest request) {
        requireMaster(user);
        return ApiResponse.success(resolutionService.resolve(new ResolveChargeCommand(chargeId, user.userId(),
                idempotencyKey, request.action(), request.reason(), request.reconciliationResultId())));
    }

    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
