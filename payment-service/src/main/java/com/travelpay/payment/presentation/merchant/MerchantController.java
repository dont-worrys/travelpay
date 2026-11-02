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

@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @PostMapping
    public ResponseEntity<ApiResponse<MerchantResponse>> createMerchant(@CurrentUser AuthenticatedUser user,
                                                                        @RequestBody @Valid CreateMerchantRequest request) {
        requireMaster(user);
        MerchantResult result = merchantService.createMerchant(request.toCommand());
        HttpStatus status = result.status() == MerchantStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.success(MerchantResponse.from(result)));
    }

    @PostMapping("/{merchantId}/ledger-account")
    public ResponseEntity<ApiResponse<MerchantResponse>> retryLedgerAccount(@CurrentUser AuthenticatedUser user,
                                                                            @PathVariable UUID merchantId) {
        requireMaster(user);
        MerchantResult result = merchantService.retryLedgerAccount(merchantId);
        return ResponseEntity.ok(ApiResponse.success(MerchantResponse.from(result)));
    }

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
