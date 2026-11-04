package com.travelpay.wallet.presentation.charge;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.charge.ChargeService;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.presentation.charge.dto.AdminChargeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** 관리자(MASTER) 충전 조회. 최신순 고정, 기간은 createdAt 기준 [from, to). 마이크로초보다 세밀한 시각은 다음 마이크로초로 올려 조회 */
@RestController
@RequestMapping("/api/v1/admin/charges")
@RequiredArgsConstructor
public class AdminChargeController {

    private final ChargeService chargeService;

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

    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
