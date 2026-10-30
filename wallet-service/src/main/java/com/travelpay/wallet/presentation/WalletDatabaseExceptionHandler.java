package com.travelpay.wallet.presentation;

import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * DB 연결 불가·커넥션 획득 실패·쿼리/트랜잭션 타임아웃을 503으로 돌려준다.
 * 호출자는 "결과 모름"으로 보고 같은 요청을 재시도할 수 있다 (지갑 개설은 멱등).
 * common GlobalExceptionHandler가 DB 장애 예외를 처리하지 않아 임시로 둠. 추후 common 처리기로 이전 예정.
 * 그 밖의 예외는 여기서 잡지 않고 common 처리기(400/404/500)로 넘긴다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class WalletDatabaseExceptionHandler {

    @ExceptionHandler({
            CannotCreateTransactionException.class,
            TransactionTimedOutException.class,
            DataAccessResourceFailureException.class,
            TransientDataAccessException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleDatabaseUnavailable(Exception exception) {
        log.error("Database unavailable: {}", exception.getClass().getName());
        return ResponseEntity.status(CommonErrorCode.SERVICE_UNAVAILABLE.getStatus())
                .body(ApiResponse.failure(ErrorResponse.of(CommonErrorCode.SERVICE_UNAVAILABLE)));
    }
}
