package com.travelpay.payment.transfer.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.transfer.application.TransferAdminService;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.AdminTransferHistoryResponse;
import com.travelpay.payment.transfer.dto.AdminTransferOrderType;
import com.travelpay.payment.transfer.dto.AdminTransferResolveRequest;
import com.travelpay.payment.transfer.dto.AdminTransferResponse;
import com.travelpay.payment.transfer.dto.AdminTransferSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 송금 조회 API입니다. 경로의 admin은 관리 기능 영역, 허용 역할은 MASTER이며 권한은 서비스 계층에서 확인합니다.
 * 응답 본문은 공통 ApiResponse이며, 문서의 응답 코드 설명(Swagger의 ApiResponse)은 이름이 겹쳐 전체 이름으로 씁니다.
 */
@Tag(name = "송금 관리", description = "관리자(MASTER)의 송금 조회·MANUAL_REVIEW 확정")
@RestController
@RequestMapping("/api/v1/admin/transfers")
public class AdminTransferController {

    private final TransferAdminService transferAdminService;

    public AdminTransferController(TransferAdminService transferAdminService) {
        this.transferAdminService = transferAdminService;
    }

    @Operation(summary = "관리자 송금 목록 조회", description = """
            모든 사용자의 송금을 기간·통화·상태·회원 조건으로 조회합니다. 조건은 모두 선택입니다.
            - `from`은 포함, `to`는 미포함이며 ISO-8601(예: `2026-11-01T00:00:00Z`)입니다.
            - `userId`는 송금인 또는 수취인입니다.
            - 정렬은 생성 시각 기준이며 같은 시각은 ID로 정렬합니다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`COMMON_INVALID_REQUEST`(page < 0, size < 1, from ≥ to, 잘못된 시각·상태·정렬 값), "
                            + "`TRANSFER_UNSUPPORTED_CURRENCY`"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER가 아님")
    })
    @GetMapping
    public ApiResponse<PageResponse<AdminTransferResponse>> list(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "생성 시각 하한(포함, ISO-8601)", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "생성 시각 상한(미포함, ISO-8601)", example = "2026-11-05T00:00:00Z")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "통화 코드(KRW·JPY·CNY)", example = "KRW")
            @RequestParam(required = false) String currency,
            @Parameter(description = "송금 상태")
            @RequestParam(required = false) TransferStatus status,
            @Parameter(description = "송금인 또는 수취인 회원 ID")
            @RequestParam(required = false) UUID userId,
            @Parameter(description = "`LATEST` 생성 시각 내림차순(기본), `OLDEST` 오름차순")
            @RequestParam(required = false) AdminTransferOrderType orderType,
            @Parameter(description = "페이지 번호(0부터)")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기(기본 20, 100을 넘으면 100으로 조회)")
            @RequestParam(defaultValue = "20") @Min(1) int size
    ) {
        AdminTransferSearch search = new AdminTransferSearch(from, to, currency, status, userId);
        return ApiResponse.success(transferAdminService.list(user, search, orderType, page, size));
    }

    @Operation(summary = "관리자 송금 상세 조회",
            description = "송금 당사자가 아니어도 조회합니다. 지갑 ID·실패/검토 사유·시도 횟수를 포함합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`TRANSFER_NOT_FOUND` 송금 없음")
    })
    @GetMapping("/{transferId}")
    public ApiResponse<AdminTransferResponse> get(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "송금 ID") @PathVariable UUID transferId
    ) {
        return ApiResponse.success(transferAdminService.get(user, transferId));
    }

    @Operation(summary = "MANUAL_REVIEW 송금 확정", description = """
            자동 처리를 멈춘 송금(`MANUAL_REVIEW`)을 운영자가 확정합니다. \
            서버가 원장 거래 결과(reference `TRANSFER` + 송금 ID)를 직접 조회해 요청과 맞는지 확인합니다.

            | 원장 결과 | 허용하는 `targetStatus` | `ledgerTransactionId` |
            |---|---|---|
            | POSTED | `COMPLETED` | 원장 거래 ID와 같아야 함 |
            | REJECTED | `FAILED`(실패 사유는 원장 거절 사유) | 원장 거래 ID와 같아야 함 |
            | 기록 없음 | `PROCESSING`(시도 횟수 0, 자동 재처리) | 보내지 않음 |

            원장이 요청을 받지 않은 송금(`reviewReason=LEDGER_REQUEST_NOT_ACCEPTED`)에 같은 reference 거래가 있으면 \
            내용이 다를 수 있어 확정하지 않습니다(409). 확정 내용은 상태 변경 이력에 남습니다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`COMMON_INVALID_REQUEST` 허용하지 않는 targetStatus, 사유 누락·255자 초과, "
                            + "ledgerTransactionId 누락(COMPLETED·FAILED) 또는 불필요한 값(PROCESSING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`TRANSFER_NOT_FOUND` 송금 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`COMMON_CONFLICT` MANUAL_REVIEW가 아님(이미 확정됨 포함), "
                            + "`TRANSFER_RESOLUTION_MISMATCH` 원장 결과와 요청이 맞지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "`COMMON_BAD_GATEWAY` 원장 응답 해석 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "`COMMON_SERVICE_UNAVAILABLE` 원장 연결 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504",
                    description = "`COMMON_GATEWAY_TIMEOUT` 원장 응답 시간 초과")
    })
    @PostMapping("/{transferId}/resolve")
    public ApiResponse<AdminTransferResponse> resolve(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "송금 ID") @PathVariable UUID transferId,
            @Valid @RequestBody AdminTransferResolveRequest request
    ) {
        return ApiResponse.success(transferAdminService.resolve(user, transferId, request));
    }

    @Operation(summary = "송금 상태 변경 이력 조회", description = "운영자 확정 이력을 기록 순서대로 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 회원 헤더 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` MASTER가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`TRANSFER_NOT_FOUND` 송금 없음")
    })
    @GetMapping("/{transferId}/history")
    public ApiResponse<List<AdminTransferHistoryResponse>> history(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "송금 ID") @PathVariable UUID transferId
    ) {
        return ApiResponse.success(transferAdminService.history(user, transferId));
    }
}
