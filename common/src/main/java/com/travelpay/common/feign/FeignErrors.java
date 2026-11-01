package com.travelpay.common.feign;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import feign.FeignException;
import feign.RetryableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 다른 서비스 호출이 실패했을 때 오류 코드를 읽고, 거절인지 확인하고, 공통 오류로 바꿉니다.
 * 각 서비스는 아는 오류 코드만 자기 오류로 바꾸고 나머지는 {@link #toCommonException}에 맡깁니다.
 */
public final class FeignErrors {

    private FeignErrors() {
    }

    /** 공통 실패 응답의 error.code를 읽습니다. 본문이 없거나 공통 형식이 아니면 empty를 반환합니다. */
    public static Optional<String> errorCode(FeignException exception) {
        Objects.requireNonNull(exception, "exception");
        // 응답에 Retry-After 헤더가 있으면 Feign이 예외를 한 겹 더 감싸서, 본문이 있는 예외를 찾아 읽습니다.
        String body = findCause(exception, FeignException.class, cause -> !cause.contentUTF8().isEmpty())
                .map(FeignException::contentUTF8)
                .orElse("");
        try {
            JsonNode code = JsonMapper.shared().readTree(body).path("error").path("code");
            if (code.isString() && !code.stringValue().isBlank()) {
                return Optional.of(code.stringValue());
            }
            return Optional.empty();
        } catch (JacksonException ignored) {
            // HTML 오류 페이지처럼 JSON이 아닌 본문은 오류 코드가 없는 것으로 봅니다.
            return Optional.empty();
        }
    }

    public static boolean hasErrorCode(FeignException exception, String code) {
        Objects.requireNonNull(code, "code");
        return errorCode(exception).filter(code::equals).isPresent();
    }

    /**
     * 상대 서비스가 요청을 받아 거절했는지(4xx) 확인합니다. 거절된 요청은 적용되지 않았습니다.
     * 그 밖의 실패는 상대가 처리했을 수 있으므로, 돈이 움직이는 호출은 되돌리지 말고 같은 멱등 키로 다시 보내거나 결과를 조회합니다.
     * 503과 연결 실패도 마찬가지입니다. 우리 서비스는 결과를 모를 때도 503을 돌려주고,
     * 본문 없는 요청은 JDK가 몰래 한 번 더 보내서, 상대가 이미 받은 요청도 연결 실패로 보일 수 있습니다.
     */
    public static boolean isRejected(FeignException exception) {
        Objects.requireNonNull(exception, "exception");
        return exception.status() >= 400 && exception.status() < 500;
    }

    /**
     * 서비스가 따로 처리하지 않은 실패를 공통 오류로 바꾸고 원래 예외는 원인으로 남깁니다.
     * 시간 초과와 상대의 504는 504, 응답을 받지 못한 실패와 상대의 503은 503, 나머지는 502입니다.
     */
    public static BusinessException toCommonException(FeignException exception) {
        Objects.requireNonNull(exception, "exception");
        int status = exception.status();
        CommonErrorCode errorCode;
        if (status == 504 || findCause(exception, SocketTimeoutException.class, cause -> true).isPresent()) {
            errorCode = CommonErrorCode.GATEWAY_TIMEOUT;
        } else if (status == 503 || (status < 0 && exception instanceof RetryableException)) {
            // 응답을 받지 못한 입출력 실패(연결 거부 등)는 Feign이 상태 -1인 RetryableException으로 알려 줍니다.
            errorCode = CommonErrorCode.SERVICE_UNAVAILABLE;
        } else {
            errorCode = CommonErrorCode.BAD_GATEWAY;
        }
        return new BusinessException(errorCode, exception);
    }

    /** 예외와 그 원인들을 차례로 보며 조건에 맞는 첫 예외를 찾습니다. 원인이 서로를 가리켜도 멈춥니다. */
    private static <T extends Throwable> Optional<T> findCause(Throwable failure, Class<T> type,
                                                               Predicate<T> condition) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (type.isInstance(current) && condition.test(type.cast(current))) {
                return Optional.of(type.cast(current));
            }
        }
        return Optional.empty();
    }
}
