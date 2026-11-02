package com.travelpay.wallet.infra.client.user;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.UserResponseException;
import com.travelpay.wallet.domain.WalletErrorCode;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static com.travelpay.wallet.infra.client.FeignFailures.connectionRefused;
import static com.travelpay.wallet.infra.client.FeignFailures.error;
import static com.travelpay.wallet.infra.client.FeignFailures.response;
import static com.travelpay.wallet.infra.client.FeignFailures.timeout;
import static com.travelpay.wallet.infra.client.FeignFailures.undecodable;
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
    private final UserRealNameClientAdapter adapter = new UserRealNameClientAdapter(feignClient);

    private final UUID userId = UUID.randomUUID();

    @Test
    void 정상_응답이면_요청자_헤더를_보내고_실명을_돌려준다() {
        when(feignClient.getFundingIdentity(userId, userId, "USER")).thenReturn(
                ApiResponse.success(new UserFundingIdentityResponse(userId, " " + REAL_NAME + " ")));

        assertEquals(REAL_NAME, adapter.findRealName(userId));
        verify(feignClient).getFundingIdentity(userId, userId, "USER");
    }

    @Test
    void 다른_회원의_응답이면_500(CapturedOutput output) {
        when(feignClient.getFundingIdentity(any(), any(), any())).thenReturn(
                ApiResponse.success(new UserFundingIdentityResponse(UUID.randomUUID(), REAL_NAME)));

        assertThrows(UserResponseException.class, () -> adapter.findRealName(userId));
        assertTrue(output.getOut().contains("ERROR"));
        assertFalse(output.getOut().contains(REAL_NAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_ACCOUNT_SUSPENDED", "USER_ACCOUNT_CLOSED"})
    void 정지_탈퇴_회원이면_409(String code) {
        givenFailure(error(403, code));

        assertBusiness(WalletErrorCode.CHARGE_USER_NOT_ACTIVE);
    }

    @Test
    void 없는_회원이면_409와_WARN(CapturedOutput output) {
        givenFailure(error(404, "USER_NOT_FOUND"));

        assertBusiness(WalletErrorCode.CHARGE_USER_NOT_ACTIVE);
        assertTrue(output.getOut().contains("WARN"));
        assertTrue(output.getOut().contains("userId=" + userId));
    }

    @Test
    void 코드가_다른_404는_502와_ERROR(CapturedOutput output) {
        givenFailure(error(404, "NOT_FOUND"));

        assertBusiness(CommonErrorCode.BAD_GATEWAY);
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("code=NOT_FOUND"));
    }

    @Test
    void 본문_없는_404는_502() {
        givenFailure(response(404, ""));

        assertBusiness(CommonErrorCode.BAD_GATEWAY);
    }

    @Test
    void 본문_없는_403은_502와_ERROR(CapturedOutput output) {
        givenFailure(response(403, ""));

        assertBusiness(CommonErrorCode.BAD_GATEWAY);
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("status=403"));
        assertTrue(output.getOut().contains("code=null"));
    }

    @ParameterizedTest
    @CsvSource({"400, INVALID_INPUT", "403, FORBIDDEN", "409, CONFLICT"})
    void 그_밖의_4xx는_502와_ERROR(int status, String code, CapturedOutput output) {
        givenFailure(error(status, code));

        assertBusiness(CommonErrorCode.BAD_GATEWAY);
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("userId=" + userId));
        assertTrue(output.getOut().contains("status=" + status));
    }

    @Test
    void 본문_해석_실패는_502와_ERROR(CapturedOutput output) {
        givenFailure(undecodable());

        assertBusiness(CommonErrorCode.BAD_GATEWAY);
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("status=200"));
    }

    @Test
    void 상대_503은_503() {
        givenFailure(error(503, "SERVICE_UNAVAILABLE"));

        assertBusiness(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502})
    void 상대_500_502는_502(int status, CapturedOutput output) {
        givenFailure(response(status, ""));

        assertBusiness(CommonErrorCode.BAD_GATEWAY);
        assertFalse(output.getOut().contains("ERROR"));
    }

    @Test
    void 상대_504는_504() {
        givenFailure(response(504, ""));

        assertBusiness(CommonErrorCode.GATEWAY_TIMEOUT);
    }

    @Test
    void 시간_초과는_504() {
        givenFailure(timeout());

        assertBusiness(CommonErrorCode.GATEWAY_TIMEOUT);
    }

    @Test
    void 연결_실패는_503() {
        givenFailure(connectionRefused());

        assertBusiness(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private void givenFailure(FeignException failure) {
        when(feignClient.getFundingIdentity(any(), any(), any())).thenThrow(failure);
    }

    private void assertBusiness(Object errorCode) {
        BusinessException e = assertThrows(BusinessException.class, () -> adapter.findRealName(userId));
        assertEquals(errorCode, e.getErrorCode());
    }
}
