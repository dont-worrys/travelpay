package com.travelpay.common.error;

public enum CommonErrorCode implements ErrorCode {

    INVALID_REQUEST("COMMON_INVALID_REQUEST", "잘못된 요청입니다.", 400),
    UNAUTHORIZED("COMMON_UNAUTHORIZED", "로그인이 필요합니다.", 401),
    FORBIDDEN("COMMON_FORBIDDEN", "요청을 수행할 권한이 없습니다.", 403),
    CSRF_INVALID("COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.", 403),
    NOT_FOUND("COMMON_NOT_FOUND", "요청한 대상을 찾을 수 없습니다.", 404),
    METHOD_NOT_ALLOWED("COMMON_METHOD_NOT_ALLOWED", "지원하지 않는 요청 메소드입니다.", 405),
    NOT_ACCEPTABLE("COMMON_NOT_ACCEPTABLE", "지원하지 않는 응답 형식입니다.", 406),
    CONFLICT("COMMON_CONFLICT", "현재 상태에서 요청을 처리할 수 없습니다.", 409),
    PAYLOAD_TOO_LARGE("COMMON_PAYLOAD_TOO_LARGE", "요청 크기가 허용 범위를 초과했습니다.", 413),
    UNSUPPORTED_MEDIA_TYPE("COMMON_UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.", 415),
    TOO_MANY_REQUESTS("COMMON_TOO_MANY_REQUESTS", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", 429),
    INTERNAL_SERVER_ERROR("COMMON_INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.", 500),
    BAD_GATEWAY("COMMON_BAD_GATEWAY", "연결된 서비스의 응답을 처리할 수 없습니다.", 502),
    SERVICE_UNAVAILABLE("COMMON_SERVICE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다.", 503),
    GATEWAY_TIMEOUT("COMMON_GATEWAY_TIMEOUT", "연결된 서비스의 응답 시간이 초과됐습니다.", 504);

    private final String code;
    private final String message;
    private final int status;

    CommonErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public int getStatus() {
        return status;
    }
}
