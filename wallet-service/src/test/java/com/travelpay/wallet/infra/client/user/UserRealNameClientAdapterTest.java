package com.travelpay.wallet.infra.client.user;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.wallet.application.charge.UserResponseException;
import com.travelpay.wallet.domain.WalletErrorCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class UserRealNameClientAdapterTest {

    private static final String REAL_NAME = "홍길동";

    private final UserFeignClient feignClient = mock(UserFeignClient.class);
    private final UserRealNameClientAdapter adapter =
            new UserRealNameClientAdapter(feignClient, JsonMapper.builder().build());

    private final UUID userId = UUID.randomUUID();

    @Test
    void 정상_응답이면_요청자_헤더를_보내고_실명을_돌려준다() {
        when(feignClient.getFundingIdentity(userId, userId, "USER")).thenReturn(
                new UserApiResponse<>(true, new UserFundingIdentityResponse(userId, " " + REAL_NAME + " "), null));

        assertEquals(REAL_NAME, adapter.findRealName(userId));
        verify(feignClient).getFundingIdentity(userId, userId, "USER");
    }

    @Test
    void 다른_회원의_응답이면_500(CapturedOutput output) {
        when(feignClient.getFundingIdentity(any(), any(), any())).thenReturn(
                new UserApiResponse<>(true, new UserFundingIdentityResponse(UUID.randomUUID(), REAL_NAME), null));

        assertThrows(UserResponseException.class, () -> adapter.findRealName(userId));
        assertTrue(output.getOut().contains("ERROR"));
        assertFalse(output.getOut().contains(REAL_NAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_ACCOUNT_SUSPENDED", "USER_ACCOUNT_CLOSED"})
    void 정지_탈퇴_회원이면_409(String code) {
        givenError(403, code);

        assertBusiness(WalletErrorCode.CHARGE_USER_NOT_ACTIVE);
    }

    @Test
    void 없는_회원이면_409와_WARN(CapturedOutput output) {
        givenError(404, "USER_NOT_FOUND");

        assertBusiness(WalletErrorCode.CHARGE_USER_NOT_ACTIVE);
        assertTrue(output.getOut().contains("WARN"));
        assertTrue(output.getOut().contains("userId=" + userId));
    }

    @Test
    void 코드가_다른_404는_500과_ERROR(CapturedOutput output) {
        givenError(404, "NOT_FOUND");

        assertThrows(UserResponseException.class, () -> adapter.findRealName(userId));
        assertTrue(output.getOut().contains("ERROR"));
    }

    @Test
    void 본문_없는_404는_500() {
        when(feignClient.getFundingIdentity(any(), any(), any())).thenThrow(feignError(404, ""));

        assertThrows(UserResponseException.class, () -> adapter.findRealName(userId));
    }

    @ParameterizedTest
    @CsvSource({"400, INVALID_INPUT", "403, FORBIDDEN", "409, CONFLICT"})
    void 그_밖의_4xx는_500과_ERROR(int status, String code, CapturedOutput output) {
        givenError(status, code);

        assertThrows(UserResponseException.class, () -> adapter.findRealName(userId));
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("status=" + status));
    }

    @Test
    void 서버_오류는_503() {
        givenError(503, "SERVICE_UNAVAILABLE");

        assertBusiness(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void 타임아웃_연결_실패는_503() {
        when(feignClient.getFundingIdentity(any(), any(), any())).thenThrow(new RetryableException(
                -1, "timeout", Request.HttpMethod.GET, (Long) null, request()));

        assertBusiness(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private void givenError(int status, String code) {
        String body = "{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code + "\",\"message\":\"m\"}}";
        when(feignClient.getFundingIdentity(any(), any(), any())).thenThrow(feignError(status, body));
    }

    private void assertBusiness(Object errorCode) {
        BusinessException e = assertThrows(BusinessException.class, () -> adapter.findRealName(userId));
        assertEquals(errorCode, e.getErrorCode());
    }

    private static FeignException feignError(int status, String body) {
        Response response = Response.builder()
                .status(status)
                .request(request())
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("UserFeignClient#getFundingIdentity", response);
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "/internal/v1/users/x/funding-identity",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
