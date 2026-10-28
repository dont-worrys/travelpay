package com.travelpay.common.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        var errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.failure(ErrorResponse.of(errorCode)));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(),
                        error.isBindingFailure() ? "올바른 값을 입력해 주세요."
                                : Objects.requireNonNullElse(error.getDefaultMessage(), "올바른 값을 입력해 주세요.")))
                .toList();
        return handleExceptionInternal(exception,
                ErrorResponse.of(CommonErrorCode.INVALID_REQUEST, details), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        var error = body instanceof ErrorResponse errorResponse
                ? errorResponse : ErrorResponse.fromHttpStatus(status.value());
        return super.handleExceptionInternal(exception, ApiResponse.failure(error), headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof BusinessException businessException) {
                return handleBusinessException(businessException);
            }
            if (current instanceof org.springframework.web.ErrorResponse frameworkError) {
                var status = frameworkError.getStatusCode();
                return ResponseEntity.status(status)
                        .headers(frameworkError.getHeaders())
                        .body(ApiResponse.failure(ErrorResponse.fromHttpStatus(status.value())));
            }
            var responseStatus = AnnotatedElementUtils.findMergedAnnotation(current.getClass(), ResponseStatus.class);
            if (responseStatus != null) {
                return ResponseEntity.status(responseStatus.code())
                        .body(ApiResponse.failure(ErrorResponse.fromHttpStatus(responseStatus.code().value())));
            }
        }
        logger.error("Unexpected exception type: " + exception.getClass().getName());
        return ResponseEntity.status(500)
                .body(ApiResponse.failure(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)));
    }
}
