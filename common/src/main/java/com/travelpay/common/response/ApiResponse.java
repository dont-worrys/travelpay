package com.travelpay.common.response;

import com.travelpay.common.error.ErrorResponse;

/** 업무 API의 성공 데이터 또는 오류 정보를 담는 공통 응답입니다. */
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

    public ApiResponse {
        if (success && error != null) {
            throw new IllegalArgumentException("Success response must not contain an error");
        }
        if (!success && data != null) {
            throw new IllegalArgumentException("Failure response must not contain data");
        }
        if (!success && error == null) {
            throw new IllegalArgumentException("Failure response must contain an error");
        }
    }

    /** 반환할 데이터가 없는 성공 응답은 null을 사용할 수 있습니다. */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 실패 응답에는 성공 데이터가 없으므로 Void 타입으로 반환합니다. */
    public static ApiResponse<Void> failure(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
