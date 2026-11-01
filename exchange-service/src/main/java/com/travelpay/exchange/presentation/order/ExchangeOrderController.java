package com.travelpay.exchange.presentation.order;

import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.application.order.ExchangeOrderService;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exchanges")
@RequiredArgsConstructor
public class ExchangeOrderController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ExchangeOrderService orderService;

    /**
     * 견적으로 환전·재환전을 신청합니다. 응답 코드는 주문 상태로 정해, 같은 멱등 키 재요청도 같은 상태면 처음과 같은 응답을 받습니다.
     * - COMPLETED: 200
     * - PROCESSING·MANUAL_REVIEW: 202 (아직 확정 아님, 조회 API로 결과 확인)
     * - FAILED: 422 + 실패 사유별 EXCHANGE_LEDGER_REJECTED_* 오류 (송금과 같은 방식)
     * 헤더가 없으면 형식 오류와 같은 EXCHANGE_INVALID_IDEMPOTENCY_KEY(400)입니다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ExchangeOrderResponse>> place(
            @CurrentUser AuthenticatedUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExchangeOrderRequest request) {
        ExchangeOrder order = orderService.place(request.toCommand(user.userId(), idempotencyKey));
        HttpStatus status = switch (order.getStatus()) {
            case COMPLETED -> HttpStatus.OK;
            case PROCESSING, MANUAL_REVIEW -> HttpStatus.ACCEPTED;
            case FAILED -> throw new BusinessException(order.getFailureReason().errorCode());
        };
        return ResponseEntity.status(status).body(ApiResponse.success(ExchangeOrderResponse.from(order)));
    }

    /** 본인 주문을 조회합니다. 다른 사람의 주문은 없는 주문과 같게 404입니다. */
    @GetMapping("/{orderId}")
    public ApiResponse<ExchangeOrderResponse> get(@CurrentUser AuthenticatedUser user, @PathVariable UUID orderId) {
        return ApiResponse.success(ExchangeOrderResponse.from(orderService.find(user.userId(), orderId)));
    }
}
