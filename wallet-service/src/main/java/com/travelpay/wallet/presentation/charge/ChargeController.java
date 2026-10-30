package com.travelpay.wallet.presentation.charge;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.ChargeService;
import com.travelpay.wallet.application.charge.dto.ChargeAcceptedResult;
import com.travelpay.wallet.application.charge.dto.RequestChargeCommand;
import com.travelpay.wallet.presentation.charge.dto.ChargeAcceptedResponse;
import com.travelpay.wallet.presentation.charge.dto.ChargeCreateRequest;
import com.travelpay.wallet.presentation.charge.dto.ChargeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping("/api/v1/charges")
@RequiredArgsConstructor
public class ChargeController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ChargeService chargeService;

    /** 접수만 한다. 처리 중이면 202, 같은 키로 이미 끝난 충전이면 200 */
    @PostMapping
    public ResponseEntity<ApiResponse<ChargeAcceptedResponse>> requestCharge(
            @CurrentUser AuthenticatedUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ChargeCreateRequest request) {
        UUID userId = requireChargeUser(user);
        RequestChargeCommand command = new RequestChargeCommand(userId, idempotencyKey, request.amount(),
                request.currency(), request.bankCode(), request.accountNumber());
        ChargeAcceptedResult result = callLogging(userId, null, () -> chargeService.requestCharge(command));
        return ResponseEntity.status(result.inProgress() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResponse.success(ChargeAcceptedResponse.from(result)));
    }

    @GetMapping("/{chargeId}")
    public ApiResponse<ChargeResponse> getCharge(@CurrentUser AuthenticatedUser user, @PathVariable UUID chargeId) {
        UUID userId = requireChargeUser(user);
        return ApiResponse.success(ChargeResponse.from(
                callLogging(userId, chargeId, () -> chargeService.getCharge(userId, chargeId))));
    }

    /** 충전은 회원(USER)만 사용한다 */
    private UUID requireChargeUser(AuthenticatedUser user) {
        if (user.role() == AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return user.userId();
    }

    /** 트랜잭션 시작 실패까지 잡히도록 서비스 바깥에서 기록. 예외 메시지는 SQL·계좌번호가 섞일 수 있어 남기지 않음 */
    private <T> T callLogging(UUID userId, UUID chargeId, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("충전 처리 실패 userId={} chargeId={} exception={}", userId, chargeId, e.getClass().getName());
            throw e;
        }
    }
}
