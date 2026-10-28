package com.travelpay.common.error;

import java.util.List;

public record ErrorResponse(String code, String message, List<FieldError> errors) {

    public ErrorResponse {
        errors = List.copyOf(errors);
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), errors);
    }

    /** 프레임워크 오류도 같은 응답으로 표현하며 HTTP 상태 자체는 처리기에서 보존합니다. */
    public static ErrorResponse fromHttpStatus(int status) {
        for (CommonErrorCode errorCode : CommonErrorCode.values()) {
            if (errorCode.getStatus() == status) {
                return of(errorCode);
            }
        }
        return new ErrorResponse("COMMON_HTTP_ERROR", "요청을 처리할 수 없습니다.", List.of());
    }

    public record FieldError(String field, String message) {
    }
}
