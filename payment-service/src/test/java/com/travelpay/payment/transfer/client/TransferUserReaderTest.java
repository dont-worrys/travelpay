package com.travelpay.payment.transfer.client;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.user.UserFeignApi;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferUserReaderTest {

    private static final UUID USER_ID = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");
    private static final UUID OTHER_USER_ID = UUID.fromString("9b2f4c1e-7d3a-4e8b-a1c5-0f6d2e9b8a74");
    private static final String RECIPIENT_NOT_FOUND_BODY = """
            {"success":false,"data":null,"error":{"code":"USER_RECIPIENT_NOT_FOUND","message":"수취인을 찾을 수 없습니다.","details":[]}}
            """;

    private UserFeignApi userFeignApi;
    private TransferUserReader userReader;

    @BeforeEach
    void setUp() {
        userFeignApi = mock(UserFeignApi.class);
        userReader = new TransferUserReader(userFeignApi);
    }

    @Test
    void returnsActiveRecipient() {
        when(userFeignApi.getActiveUser(USER_ID))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(USER_ID, "홍길동")));

        assertEquals(Optional.of(new UserRecipientResponse(USER_ID, "홍길동")), userReader.findRecipient(USER_ID));
    }

    @Test
    void treatsRecipientNotFoundAsEmpty() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(404, RECIPIENT_NOT_FOUND_BODY));

        assertEquals(Optional.empty(), userReader.findRecipient(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":false,\"data\":null,\"error\":{\"code\":\"COMMON_NOT_FOUND\",\"message\":\"없음\",\"details\":[]}}",
            "{\"success\":false,\"data\":null,\"error\":{\"code\":\"USER_NOT_FOUND\",\"message\":\"없음\",\"details\":[]}}",
            "<html>Not Found</html>",
            ""
    })
    void reportsNotFoundOtherThanMissingRecipientAsBadGateway(String body) {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(404, body));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 500})
    void doesNotTreatRecipientNotFoundCodeWithOtherStatusAsEmpty(int status) {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(status, RECIPIENT_NOT_FOUND_BODY));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @Test
    void reportsRecipientNotFoundCodeWithServiceUnavailableStatusAsServiceUnavailable() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(503, RECIPIENT_NOT_FOUND_BODY));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void keepsUserServiceRateLimitAsTooManyRequests() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(429, """
                {"success":false,"data":null,"error":{"code":"COMMON_TOO_MANY_REQUESTS","message":"요청이 너무 많습니다.","details":[]}}
                """));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.TOO_MANY_REQUESTS, exception.getErrorCode());
    }

    @Test
    void reportsConnectionProblemsAsServiceUnavailable() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(new RetryableException(-1, "Connection refused",
                Request.HttpMethod.GET, new ConnectException("Connection refused"), (Long) null, request()));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void reportsTimeoutAsGatewayTimeout() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(new RetryableException(-1, "Read timed out",
                Request.HttpMethod.GET, new SocketTimeoutException("Read timed out"), (Long) null, request()));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.GATEWAY_TIMEOUT, exception.getErrorCode());
    }

    @Test
    void reportsUserServiceGatewayTimeoutAsGatewayTimeout() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(504, null));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.GATEWAY_TIMEOUT, exception.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Load balancer does not contain an instance for the service user-service",
            "{\"success\":false,\"data\":null,\"error\":{\"code\":\"COMMON_SERVICE_UNAVAILABLE\",\"message\":\"서비스를 일시적으로 사용할 수 없습니다.\",\"details\":[]}}"
    })
    void reportsUserServiceUnavailableAsServiceUnavailable(String body) {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(503, body));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void reportsOtherUserServiceErrorsAsBadGateway() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(500, null));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @Test
    void reportsUnexpectedResponseBodyAsBadGateway() {
        when(userFeignApi.getActiveUser(USER_ID))
                .thenReturn(null)
                .thenReturn(new ApiResponse<>(false, null, ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)))
                .thenReturn(ApiResponse.success(null))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(USER_ID, null)))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(USER_ID, "  ")))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(null, "홍길동")))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(OTHER_USER_ID, "홍길동")));

        for (int i = 0; i < 7; i++) {
            var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));
            assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
        }
    }

    @Test
    void doesNotExposeRealNameInStringRepresentation() {
        assertFalse(new UserRecipientResponse(USER_ID, "홍길동").toString().contains("홍길동"));
        assertFalse(new UserFeignApi.UserResponse(USER_ID, "홍길동").toString().contains("홍길동"));
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET,
                "http://user-service/internal/v1/users/" + USER_ID + "/real-name", Map.of(), null, UTF_8, null);
    }

    private static FeignException feignError(int status, String body) {
        Response.Builder builder = Response.builder()
                .status(status)
                .reason("error")
                .request(request())
                .headers(Map.of());
        if (body != null) {
            builder.body(body, UTF_8);
        }
        return FeignException.errorStatus("UserFeignApi#getActiveUser(UUID)", builder.build());
    }
}
