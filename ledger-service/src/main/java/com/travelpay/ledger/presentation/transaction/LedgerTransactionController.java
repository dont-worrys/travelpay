package com.travelpay.ledger.presentation.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.ledger.application.transaction.LedgerTransactionService;
import com.travelpay.ledger.application.transaction.dto.TransactionResult;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import com.travelpay.ledger.presentation.transaction.dto.RegisterTransactionRequest;
import com.travelpay.ledger.presentation.transaction.dto.RegisterTransactionResponse;
import com.travelpay.ledger.presentation.transaction.dto.TransactionResultResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping("/internal/v1/ledger/transactions")
@RequiredArgsConstructor
public class LedgerTransactionController {

    private final LedgerTransactionService transactionService;

    /**
     * 새로 기록(POSTED) 201, 같은 내용 재요청 200, 확정 거절 422.
     * 거절은 서비스 트랜잭션이 커밋된 뒤 여기서 422로 바꾼다 (거절 기록은 남고 재요청에도 같은 422).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<RegisterTransactionResponse>> register(
            @Valid @RequestBody RegisterTransactionRequest request) {
        TransactionResult result = callLogging(request.referenceType(), request.referenceId(),
                () -> transactionService.register(request.toCommand()));
        if (!result.isPosted()) {
            throw new BusinessException(LedgerErrorCode.rejected(result.rejectReason()));
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(RegisterTransactionResponse.from(result)));
    }

    @GetMapping
    public ApiResponse<TransactionResultResponse> getByReference(@RequestParam ReferenceType referenceType,
                                                                 @RequestParam UUID referenceId) {
        TransactionResult result = callLogging(referenceType, referenceId,
                () -> transactionService.getByReference(referenceType, referenceId));
        return ApiResponse.success(TransactionResultResponse.from(result));
    }

    /** 트랜잭션 시작(커넥션 획득) 실패까지 잡히도록 서비스 프록시 바깥에서 기록. 예외 메시지는 SQL이 섞일 수 있어 남기지 않음 */
    private TransactionResult callLogging(ReferenceType referenceType, UUID referenceId,
                                          Supplier<TransactionResult> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("원장 거래 처리 실패 referenceType={} referenceId={} exception={}",
                    referenceType, referenceId, e.getClass().getName());
            throw e;
        }
    }
}
