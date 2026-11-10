package com.travelpay.payment.presentation.merchant;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.application.merchant.SettlementService;
import com.travelpay.payment.presentation.merchant.dto.SettlementDetailResponse;
import com.travelpay.payment.presentation.merchant.dto.SettlementPreviewResponse;
import com.travelpay.payment.presentation.merchant.dto.SettlementResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

// 가맹점 정산 API (가맹점 주인·MASTER). 정산금은 가맹점 원장 지갑에서 주인 회원 지갑으로 갑니다.
@Tag(name = "정산", description = "가맹점 주인·MASTER의 정산 미리보기·요청·조회. 정산금은 가맹점 원장 지갑에서 주인 회원 지갑으로 감")
@RestController
@RequestMapping("/api/v1/payments/merchants/{merchantId}/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    // 정산 요청: 환불 기간이 지난 미정산 결제를 모아 정산.
    @Operation(summary = "정산 요청")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "`PAID` 주인 지갑 입금 완료, `REQUESTED` 결과 확인 중(서버가 자동으로 다시 처리), `MANUAL_REVIEW` 운영자 확인 필요")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<SettlementResponse>> requestSettlement(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID merchantId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey) {
        SettlementResponse response = SettlementResponse.from(
                settlementService.requestSettlement(user, merchantId, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // 정산 미리보기: 지금 요청하면 정산될 금액과 환불 기간을 기다리는 금액. 조회 시점 기준
    @Operation(summary = "정산 미리보기")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "`available` 지금 정산 가능한 전체, `nextSettlement` 다음 요청 한 번에 들어갈 몫(최대 1,000건), `waiting` 환불 기간을 기다리는 금액, `nextAvailableAt` 가장 빠른 정산 가능 시각")
    })
    @GetMapping("/preview")
    public ResponseEntity<ApiResponse<SettlementPreviewResponse>> previewSettlement(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID merchantId) {
        SettlementPreviewResponse response = SettlementPreviewResponse.from(
                settlementService.previewSettlement(user, merchantId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "정산 목록")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "최신순 페이지. 항목마다 포함된 결제 수(`paymentCount`)")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<SettlementResponse>>> getSettlements(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        PageResponse<SettlementResponse> response = PageResponse.from(
                settlementService.getSettlements(user, merchantId, page, size), SettlementResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "정산 상세")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "정산과 포함된 결제(`items`). `FAILED` 정산은 `items`가 비어 있음")
    })
    @GetMapping("/{settlementId}")
    public ResponseEntity<ApiResponse<SettlementDetailResponse>> getSettlement(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID merchantId,
            @PathVariable UUID settlementId) {
        SettlementDetailResponse response = SettlementDetailResponse.from(
                settlementService.getSettlement(user, merchantId, settlementId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
