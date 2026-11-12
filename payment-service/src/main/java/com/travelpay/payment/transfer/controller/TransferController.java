package com.travelpay.payment.transfer.controller;

import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.domain.TransferDirection;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.RecipientResponse;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * 송금 외부 API입니다. 회원(USER)만 사용하며, MASTER는 403입니다(wallet-service 회원 API와 같은 방식).
 * 응답 본문은 공통 ApiResponse이며, 문서의 응답 코드 설명(Swagger의 ApiResponse)은 이름이 겹쳐 전체 이름으로 씁니다.
 */
@Tag(name = "송금", description = "사용자 간 지갑 송금 요청·조회·취소")
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * 실제 자금 이동 여부는 응답의 status로 구분합니다.
     * 원장 처리 결과가 확정되지 않은 송금(PROCESSING·MANUAL_REVIEW)은 202, 그 외(REQUESTED·COMPLETED)는 201이며,
     * 원장이 거절한 송금은 422입니다.
     */
    @Operation(summary = "송금 요청", description = """
            인증된 요청자가 송금인입니다. 같은 통화의 지갑 사이에서만 송금합니다(지원 통화 KRW·JPY·CNY).

            - 실제 자금 이동 여부는 응답의 `status`로 구분합니다.
            - 같은 `Idempotency-Key`·같은 본문으로 다시 요청하면 원장을 다시 호출하지 않고 기존 송금의 현재 상태를 반환합니다.
            - 같은 키로 다른 본문을 보내면 409입니다.
            - 원장 처리 결과를 확인하지 못한 송금(`PROCESSING`)은 서버가 자동으로 다시 처리하며, \
            재시도 한도를 넘거나 원장이 요청을 받지 않으면 `MANUAL_REVIEW`가 됩니다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "송금 완료(`COMPLETED`) 또는 접수(`REQUESTED`)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "원장 처리 결과 미확정(`PROCESSING`) 또는 수동 확인 대상(`MANUAL_REVIEW`). "
                            + "상세 조회로 최종 상태를 확인합니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`TRANSFER_INVALID_IDEMPOTENCY_KEY`(키 누락·형식 오류), `TRANSFER_SELF_TRANSFER`, "
                            + "`TRANSFER_UNSUPPORTED_CURRENCY`, `COMMON_INVALID_REQUEST`(본문 검증 실패)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER는 사용자 송금 API를 쓸 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`TRANSFER_IDEMPOTENCY_KEY_REUSED` 같은 키로 다른 내용의 송금 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "`TRANSFER_SENDER_WALLET_UNAVAILABLE`, `TRANSFER_RECEIVER_UNAVAILABLE`, "
                            + "`TRANSFER_INSUFFICIENT_BALANCE`·`TRANSFER_ACCOUNT_UNAVAILABLE`(원장 거절, 송금은 `FAILED`로 기록), "
                            + "`TRANSFER_NOT_PROCESSED`(운영자가 실패로 확정한 송금의 재요청)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "`COMMON_TOO_MANY_REQUESTS` 회원 서비스 수취인 조회 호출 제한 초과(송금은 기록되지 않음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "`COMMON_BAD_GATEWAY` 지갑·회원 서비스 응답 해석 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "`COMMON_SERVICE_UNAVAILABLE` 지갑·회원 서비스 연결 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504",
                    description = "`COMMON_GATEWAY_TIMEOUT` 지갑·회원 서비스 응답 시간 초과")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> request(
            @CurrentUser AuthenticatedUser user,
            // 누락은 프레임워크 400 대신 TRANSFER_INVALID_IDEMPOTENCY_KEY로 응답하려고 required=false로 받습니다. 문서에는 필수로 표시합니다.
            @Parameter(required = true,
                    description = "중복 송금 방지 키. 공백 없는 ASCII 1~100자이며, 송금마다 새 값을 씁니다(UUID 권장).",
                    example = "6f1c2b9e-3d4a-4e5f-8a7b-1c2d3e4f5a6b")
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferCreateRequest request
    ) {
        TransferResponse response = transferService.request(requireTransferUser(user), idempotencyKey, request);
        boolean unsettled = response.status() == TransferStatus.PROCESSING
                || response.status() == TransferStatus.MANUAL_REVIEW;
        HttpStatus status = unsettled ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.success(response));
    }

    @Operation(summary = "송금 상세 조회",
            description = "송금인 또는 수취인만 조회할 수 있습니다. 그 밖의 사용자에게는 송금이 없는 것과 같이 404를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER는 사용자 송금 API를 쓸 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`TRANSFER_NOT_FOUND` 송금이 없거나 당사자가 아님")
    })
    @GetMapping("/{transferId}")
    public ApiResponse<TransferResponse> get(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "송금 ID") @PathVariable UUID transferId
    ) {
        return ApiResponse.success(transferService.get(requireTransferUser(user), transferId));
    }

    /** 송금 전 수취인 확인용이며, 마스킹한 이름과 송금 가능 여부만 반환합니다. */
    @Operation(summary = "수취인 확인", description = """
            송금 전 수취인을 확인합니다. 마스킹한 이름과 송금 가능 여부만 반환하며, 지갑 ID·지갑 상태는 반환하지 않습니다.
            수취인 지갑이 활성 상태가 아니면 200 + `transferable: false`입니다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`TRANSFER_SELF_TRANSFER` 본인 조회"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER는 사용자 송금 API를 쓸 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`TRANSFER_RECEIVER_NOT_FOUND` 활성 회원이 아니거나 지갑 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "`COMMON_TOO_MANY_REQUESTS` 회원 서비스 수취인 조회 호출 제한 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "`COMMON_BAD_GATEWAY` 지갑·회원 서비스 응답 해석 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "`COMMON_SERVICE_UNAVAILABLE` 지갑·회원 서비스 연결 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504",
                    description = "`COMMON_GATEWAY_TIMEOUT` 지갑·회원 서비스 응답 시간 초과")
    })
    @GetMapping("/recipients/{receiverId}")
    public ApiResponse<RecipientResponse> getRecipient(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "수취인 회원 ID") @PathVariable UUID receiverId
    ) {
        return ApiResponse.success(transferService.findRecipient(requireTransferUser(user), receiverId));
    }

    /** 접수 상태의 송금만 취소하며, 이미 취소된 송금은 현재 상태를 그대로 반환합니다. */
    @Operation(summary = "송금 취소", description = """
            송금인만 취소할 수 있으며, 접수(`REQUESTED`) 상태의 송금만 취소합니다.
            이미 취소된 송금은 200과 현재 상태를 그대로 반환합니다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "취소 완료 또는 이미 취소됨(`CANCELLED`)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER는 사용자 송금 API를 쓸 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`TRANSFER_NOT_FOUND` 송금이 없거나 송금인이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`COMMON_CONFLICT` 취소할 수 없는 상태(`PROCESSING`·`COMPLETED`·`FAILED`·`MANUAL_REVIEW`)")
    })
    @PostMapping("/{transferId}/cancel")
    public ApiResponse<TransferResponse> cancel(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "송금 ID") @PathVariable UUID transferId
    ) {
        return ApiResponse.success(transferService.cancel(requireTransferUser(user), transferId));
    }

    /** 생성 시각 내림차순 고정이며, page는 0부터 시작합니다. */
    @Operation(summary = "송금 내역 조회",
            description = "요청자가 보내거나 받은 송금만 반환합니다. 정렬은 생성 시각 내림차순(같으면 ID 내림차순)으로 고정입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`COMMON_INVALID_REQUEST` page < 0, size < 1, 잘못된 direction"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER는 사용자 송금 API를 쓸 수 없음")
    })
    @GetMapping
    public ApiResponse<PageResponse<TransferResponse>> list(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "`SENT` 보낸 내역, `RECEIVED` 받은 내역. 생략하면 둘 다")
            @RequestParam(required = false) TransferDirection direction,
            @Parameter(description = "페이지 번호(0부터)")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기(기본 20, 100을 넘으면 100으로 조회)")
            @RequestParam(defaultValue = "20") @Min(1) int size
    ) {
        return ApiResponse.success(transferService.list(requireTransferUser(user), direction, page, size));
    }

    /** 사용자 송금 API는 회원(USER)만 씁니다. 관리자 송금 API(/api/v1/admin/transfers)는 별도로 MASTER만 허용합니다. */
    private static UUID requireTransferUser(AuthenticatedUser user) {
        if (user.role() == AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return user.userId();
    }
}
