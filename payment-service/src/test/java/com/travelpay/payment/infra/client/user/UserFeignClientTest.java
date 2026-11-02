package com.travelpay.payment.infra.client.user;

import java.util.Map;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import feign.FeignException;
import feign.Request;
import feign.Response;
import com.travelpay.payment.support.FeignFailures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserFeignClientTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private UserFeignApi userFeignApi;
    private UserFeignClient userClient;

    @BeforeEach
    void setUp() {
        userFeignApi = mock(UserFeignApi.class);
        userClient = new UserFeignClient(userFeignApi);
    }

    @Test
    void returnsTrueForActiveUser() {
        when(userFeignApi.getActiveUser(USER_ID))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(USER_ID)));

        assertTrue(userClient.isActiveUser(USER_ID));
    }

    @Test
    void returnsFalseWhenUserIsMissingOrInactive() {
        when(userFeignApi.getActiveUser(USER_ID))
                .thenThrow(feignError(404, "{\"success\":false,\"error\":{\"code\":\"USER_RECIPIENT_NOT_FOUND\"}}"));

        assertFalse(userClient.isActiveUser(USER_ID));
    }

    @Test
    void reportsNotFoundWithOtherCodeAsBadGateway() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(404, "<html>not found</html>"));

        assertErrorCode(CommonErrorCode.BAD_GATEWAY);
    }

    @Test
    void reportsResponseForOtherUserAsBadGateway() {
        when(userFeignApi.getActiveUser(USER_ID))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(UUID.randomUUID())));

        assertErrorCode(CommonErrorCode.BAD_GATEWAY);
    }

    @Test
    void reportsEmptyDataAsBadGateway() {
        when(userFeignApi.getActiveUser(USER_ID)).thenReturn(ApiResponse.success(null));

        assertErrorCode(CommonErrorCode.BAD_GATEWAY);
    }

    @Test
    void reportsConnectionProblemsAsServiceUnavailable() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(FeignFailures.connectionRefused());

        assertErrorCode(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void reportsTimeoutAsGatewayTimeout() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(FeignFailures.readTimeout());

        assertErrorCode(CommonErrorCode.GATEWAY_TIMEOUT);
    }

    @Test
    void reportsUserServiceUnavailableAsServiceUnavailable() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(503, ""));

        assertErrorCode(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void reportsUserServiceGatewayTimeoutAsGatewayTimeout() {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(504, ""));

        assertErrorCode(CommonErrorCode.GATEWAY_TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 500, 502})
    void reportsOtherErrorsAsBadGateway(int status) {
        when(userFeignApi.getActiveUser(USER_ID)).thenThrow(feignError(status, ""));

        assertErrorCode(CommonErrorCode.BAD_GATEWAY);
    }

    private void assertErrorCode(CommonErrorCode expected) {
        var exception = assertThrows(BusinessException.class, () -> userClient.isActiveUser(USER_ID));
        assertSame(expected, exception.getErrorCode());
    }

    private static FeignException feignError(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "/internal/v1/users/" + USER_ID + "/real-name",
                Map.of(), null, UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .request(request)
                .body(body, UTF_8)
                .build();
        return FeignException.errorStatus("UserFeignApi#getActiveUser", response);
    }
}
