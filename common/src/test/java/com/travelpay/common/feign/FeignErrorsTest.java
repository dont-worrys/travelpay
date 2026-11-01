package com.travelpay.common.feign;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.travelpay.common.error.CommonErrorCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import feign.codec.EncodeException;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignErrorsTest {

    private static final Request REQUEST = Request.create(
            Request.HttpMethod.GET, "http://user-service/internal/v1/users", Map.of(), null, UTF_8, null);
    private static final String USER_NOT_FOUND_BODY = """
            {"success":false,"data":null,"error":{"code":"USER_NOT_FOUND","message":"회원을 찾을 수 없습니다.","details":[]}}
            """;

    @Test
    void readsTheErrorCodeOfTheCommonFailureResponse() {
        var exception = responseFailure(404, USER_NOT_FOUND_BODY, Map.of());

        assertEquals(Optional.of("USER_NOT_FOUND"), FeignErrors.errorCode(exception));
        assertTrue(FeignErrors.hasErrorCode(exception, "USER_NOT_FOUND"));
        assertFalse(FeignErrors.hasErrorCode(exception, "USER_RECIPIENT_NOT_FOUND"));
    }

    @Test
    void readsTheErrorCodeWhenARetryAfterHeaderMadeFeignWrapTheResponse() {
        String body = """
                {"success":false,"data":null,"error":{"code":"COMMON_TOO_MANY_REQUESTS","message":"","details":[]}}
                """;
        Response response = response(429, body, Map.of("Retry-After", List.of("1")));

        Exception decoded = new ErrorDecoder.Default().decode("UserClient#find(UUID)", response);

        var wrapped = assertInstanceOf(RetryableException.class, decoded);
        assertEquals(Optional.of("COMMON_TOO_MANY_REQUESTS"), FeignErrors.errorCode(wrapped));
        assertTrue(FeignErrors.isRejected(wrapped));
        assertEquals(CommonErrorCode.BAD_GATEWAY, FeignErrors.toCommonException(wrapped).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "<html><body>502 Bad Gateway</body></html>",
            "{\"success\":false}",
            "{\"success\":false,\"error\":{\"code\":404}}",
            "{\"success\":false,\"error\":{\"code\":\"  \"}}"
    })
    void hasNoErrorCodeWhenTheBodyIsNotTheCommonFailureResponse(String body) {
        var exception = responseFailure(502, body, Map.of());

        assertEquals(Optional.empty(), FeignErrors.errorCode(exception));
        assertFalse(FeignErrors.hasErrorCode(exception, "USER_NOT_FOUND"));
    }

    @Test
    void hasNoErrorCodeWhenNoResponseArrived() {
        assertEquals(Optional.empty(), FeignErrors.errorCode(noResponse(new ConnectException("Connection refused"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 429})
    void aClientErrorStatusMeansTheRequestWasRejected(int status) {
        var exception = responseFailure(status, USER_NOT_FOUND_BODY, Map.of());

        assertTrue(FeignErrors.isRejected(exception));
        assertEquals(CommonErrorCode.BAD_GATEWAY, FeignErrors.toCommonException(exception).getErrorCode());
    }

    static Stream<Arguments> failuresThatMayHaveBeenProcessed() {
        return Stream.of(
                Arguments.of("응답 대기 시간 초과", noResponse(new SocketTimeoutException("Read timed out")),
                        CommonErrorCode.GATEWAY_TIMEOUT),
                Arguments.of("연결 시간 초과", noResponse(new SocketTimeoutException("Connect timed out")),
                        CommonErrorCode.GATEWAY_TIMEOUT),
                Arguments.of("감싼 원인 안의 시간 초과",
                        noResponse(new IOException("wrapped", new SocketTimeoutException("Read timed out"))),
                        CommonErrorCode.GATEWAY_TIMEOUT),
                Arguments.of("상대 504", responseFailure(504, "", Map.of()), CommonErrorCode.GATEWAY_TIMEOUT),
                Arguments.of("본문을 읽다 시간 초과",
                        new DecodeException(200, "Read timed out", REQUEST, new SocketTimeoutException("Read timed out")),
                        CommonErrorCode.GATEWAY_TIMEOUT),
                Arguments.of("연결 거부", noResponse(new ConnectException("Connection refused")),
                        CommonErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("경로 없음", noResponse(new NoRouteToHostException("No route to host")),
                        CommonErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("주소 없음", noResponse(new UnknownHostException("wallet-service")),
                        CommonErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("응답 중 연결 끊김", noResponse(new SocketException("Connection reset")),
                        CommonErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("상대 503", responseFailure(503, "", Map.of()), CommonErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of("상대 500", responseFailure(500, "", Map.of()), CommonErrorCode.BAD_GATEWAY),
                Arguments.of("상대 502", responseFailure(502, "", Map.of()), CommonErrorCode.BAD_GATEWAY),
                Arguments.of("2xx 본문 해석 실패", new DecodeException(200, "본문을 해석할 수 없습니다.", REQUEST),
                        CommonErrorCode.BAD_GATEWAY),
                Arguments.of("요청 본문 변환 실패", new EncodeException("요청 본문을 만들 수 없습니다."),
                        CommonErrorCode.BAD_GATEWAY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresThatMayHaveBeenProcessed")
    void aFailureOtherThanARejectionIsNotRejectedAndBecomesTheMatchingCommonError(
            String reason, FeignException exception, CommonErrorCode expected) {
        assertFalse(FeignErrors.isRejected(exception));
        assertEquals(expected, FeignErrors.toCommonException(exception).getErrorCode());
    }

    @Test
    void cyclicCausesTerminate() {
        var first = new IOException("first");
        var second = new IOException("second", first);
        first.initCause(second);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(
                CommonErrorCode.SERVICE_UNAVAILABLE, FeignErrors.toCommonException(noResponse(first)).getErrorCode()));
    }

    @Test
    void theCommonExceptionKeepsTheFeignFailureAsItsCause() {
        var exception = responseFailure(500, "", Map.of());

        assertSame(exception, FeignErrors.toCommonException(exception).getCause());
    }

    @Test
    void rejectsMissingArguments() {
        var exception = responseFailure(404, USER_NOT_FOUND_BODY, Map.of());

        assertThrows(NullPointerException.class, () -> FeignErrors.errorCode(null));
        assertThrows(NullPointerException.class, () -> FeignErrors.isRejected(null));
        assertThrows(NullPointerException.class, () -> FeignErrors.toCommonException(null));
        assertThrows(NullPointerException.class, () -> FeignErrors.hasErrorCode(exception, null));
    }

    private static FeignException responseFailure(int status, String body, Map<String, Collection<String>> headers) {
        return FeignException.errorStatus("UserClient#find(UUID)", response(status, body, headers));
    }

    private static Response response(int status, String body, Map<String, Collection<String>> headers) {
        return Response.builder()
                .status(status)
                .reason("failure")
                .request(REQUEST)
                .headers(headers)
                .body(body, UTF_8)
                .build();
    }

    /** 응답을 받지 못하면(연결 실패·시간 초과) Feign은 원인을 RetryableException으로 감싸고 상태를 -1로 둡니다. */
    private static FeignException noResponse(IOException cause) {
        return new RetryableException(-1, cause.getMessage(), Request.HttpMethod.GET, cause, (Long) null, REQUEST);
    }
}
