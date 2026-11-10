package com.travelpay.payment.presentation.merchant;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.merchant.MerchantService;
import com.travelpay.payment.application.merchant.dto.MerchantResult;
import com.travelpay.payment.domain.merchant.MerchantStatus;
import com.travelpay.payment.presentation.merchant.dto.CreateMerchantRequest;
import com.travelpay.payment.presentation.merchant.dto.MerchantBalanceResponse;
import com.travelpay.payment.presentation.merchant.dto.MerchantResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "가맹점", description = "MASTER의 가맹점 생성·원장 계정 재시도, 가맹점 주인·MASTER의 잔액·받은 결제 조회")
@RestController
@RequestMapping("/api/v1/payments/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @Operation(summary = "가맹점 생성 (MASTER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "`ACTIVE` 생성 완료, 바로 결제 가능"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "`PENDING` 원장 계정 생성 실패. 원장 계정 재시도 API로 다시 만듦")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<MerchantResponse>> createMerchant(@CurrentUser AuthenticatedUser user,
                                                                        @RequestBody @Valid CreateMerchantRequest request) {
        requireMaster(user);
        MerchantResult result = merchantService.createMerchant(request.toCommand());
        HttpStatus status = result.status() == MerchantStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.success(MerchantResponse.from(result)));
    }

    @Operation(summary = "가맹점 원장 계정 생성 재시도 (MASTER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "가맹점. 이미 `PENDING`이 아니면 원장을 부르지 않고 현재 상태")
    })
    @PostMapping("/{merchantId}/ledger-account")
    public ResponseEntity<ApiResponse<MerchantResponse>> retryLedgerAccount(@CurrentUser AuthenticatedUser user,
                                                                            @PathVariable UUID merchantId) {
        requireMaster(user);
        MerchantResult result = merchantService.retryLedgerAccount(merchantId);
        return ResponseEntity.ok(ApiResponse.success(MerchantResponse.from(result)));
    }

    @Operation(summary = "가맹점 잔액")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "원장의 가맹점 통화 계정 잔액")
    })
    @GetMapping("/{merchantId}/balance")
    public ApiResponse<MerchantBalanceResponse> getBalance(@CurrentUser AuthenticatedUser user,
                                                           @PathVariable UUID merchantId) {
        return ApiResponse.success(MerchantBalanceResponse.from(merchantService.getBalance(user, merchantId)));
    }

    private void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
