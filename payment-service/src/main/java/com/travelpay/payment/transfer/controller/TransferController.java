package com.travelpay.payment.transfer.controller;

import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.domain.TransferDirection;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** 송금 접수만 처리하며, 실제 자금 이동 여부는 응답의 status로 구분합니다. */
    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> request(
            @CurrentUser AuthenticatedUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferCreateRequest request
    ) {
        TransferResponse response = transferService.request(user.userId(), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{transferId}")
    public ApiResponse<TransferResponse> get(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID transferId
    ) {
        return ApiResponse.success(transferService.get(user.userId(), transferId));
    }

    /** 생성 시각 내림차순 고정이며, page는 0부터 시작합니다. */
    @GetMapping
    public ApiResponse<PageResponse<TransferResponse>> list(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) TransferDirection direction,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size
    ) {
        return ApiResponse.success(transferService.list(user.userId(), direction, page, size));
    }
}
