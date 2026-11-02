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

    private UserClient userClient;
    private TransferUserReader userReader;

    @BeforeEach
    void setUp() {
        userClient = mock(UserClient.class);
        userReader = new TransferUserReader(userClient);
    }

    @Test
    void returnsActiveRecipient() {
        when(userClient.getRecipient(USER_ID))
                .thenReturn(ApiResponse.success(new UserRecipientResponse(USER_ID, "홍길동")));

        assertEquals(Optional.of(new UserRecipientResponse(USER_ID, "홍길동")), userReader.findRecipient(USER_ID));
    }

    @Test
    void treatsRecipientNotFoundAsEmpty() {
        when(userClient.getRecipient(USER_ID)).thenThrow(feignError(404, RECIPIENT_NOT_FOUND_BODY));

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
        when(userClient.getRecipient(USER_ID)).thenThrow(feignError(404, body));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @Test
    void reportsConnectionProblemsAsServiceUnavailable() {
        when(userClient.getRecipient(USER_ID)).thenThrow(new RetryableException(-1, "Connection refused",
                Request.HttpMethod.GET, new ConnectException("Connection refused"), (Long) null, request()));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void reportsTimeoutAsGatewayTimeout() {
        when(userClient.getRecipient(USER_ID)).thenThrow(new RetryableException(-1, "Read timed out",
                Request.HttpMethod.GET, new SocketTimeoutException("Read timed out"), (Long) null, request()));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.GATEWAY_TIMEOUT, exception.getErrorCode());
    }

    @Test
    void reportsUserServiceGatewayTimeoutAsGatewayTimeout() {
        when(userClient.getRecipient(USER_ID)).thenThrow(feignError(504, null));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.GATEWAY_TIMEOUT, exception.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Load balancer does not contain an instance for the service user-service",
            "{\"success\":false,\"data\":null,\"error\":{\"code\":\"COMMON_SERVICE_UNAVAILABLE\",\"message\":\"서비스를 일시적으로 사용할 수 없습니다.\",\"details\":[]}}"
    })
    void reportsUserServiceUnavailableAsServiceUnavailable(String body) {
        when(userClient.getRecipient(USER_ID)).thenThrow(feignError(503, body));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void reportsOtherUserServiceErrorsAsBadGateway() {
        when(userClient.getRecipient(USER_ID)).thenThrow(feignError(500, null));

        var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @Test
    void reportsUnexpectedResponseBodyAsBadGateway() {
        when(userClient.getRecipient(USER_ID))
                .thenReturn(new ApiResponse<>(false, null, ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)))
                .thenReturn(ApiResponse.success(null))
                .thenReturn(ApiResponse.success(new UserRecipientResponse(USER_ID, null)))
                .thenReturn(ApiResponse.success(new UserRecipientResponse(USER_ID, "  ")))
                .thenReturn(ApiResponse.success(new UserRecipientResponse(null, "홍길동")))
                .thenReturn(ApiResponse.success(new UserRecipientResponse(OTHER_USER_ID, "홍길동")));

        for (int i = 0; i < 6; i++) {
            var exception = assertThrows(BusinessException.class, () -> userReader.findRecipient(USER_ID));
            assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
        }
    }

    @Test
    void doesNotExposeRealNameInStringRepresentation() {
        assertFalse(new UserRecipientResponse(USER_ID, "홍길동").toString().contains("홍길동"));
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
        return FeignException.errorStatus("UserClient#getRecipient(UUID)", builder.build());
    }
}
