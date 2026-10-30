package com.travelpay.payment.infra.client.wallet;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.payment.client.wallet.Wallet;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletFeignClientTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID WALLET_ID = UUID.fromString("0f8e6d4c-2b1a-4f9e-8d7c-6b5a4f3e2d1c");
    private static final String WALLET_NOT_FOUND_BODY = """
            {"success":false,"data":null,"error":{"code":"WALLET_NOT_FOUND","message":"지갑을 찾을 수 없습니다.","details":[]}}
            """;

    private WalletFeignApi walletFeignApi;
    private WalletFeignClient walletClient;

    @BeforeEach
    void setUp() {
        walletFeignApi = mock(WalletFeignApi.class);
        walletClient = new WalletFeignClient(walletFeignApi, JsonMapper.builder().build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED"})
    void returnsWalletIdAndStatus(String status) {
        when(walletFeignApi.getWallet(USER_ID))
                .thenReturn(ApiResponse.success(new WalletFeignApi.WalletResponse(WALLET_ID, status)));

        assertEquals(Optional.of(new Wallet(WALLET_ID, status)),
                walletClient.findWallet(USER_ID));
    }

    @Test
    void returnsEmptyWhenWalletIsMissing() {
        when(walletFeignApi.getWallet(USER_ID)).thenThrow(feignError(404, WALLET_NOT_FOUND_BODY));

        assertEquals(Optional.empty(), walletClient.findWallet(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":false,\"data\":null,\"error\":{\"code\":\"COMMON_NOT_FOUND\",\"message\":\"없음\",\"details\":[]}}",
            "<html>Not Found</html>",
            ""
    })
    void reportsNotFoundOtherThanMissingWalletAsBadGateway(String body) {
        when(walletFeignApi.getWallet(USER_ID)).thenThrow(feignError(404, body));

        var exception = assertThrows(BusinessException.class,
                () -> walletClient.findWallet(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @Test
    void reportsConnectionProblemsAsServiceUnavailable() {
        when(walletFeignApi.getWallet(USER_ID)).thenThrow(mock(RetryableException.class));

        var exception = assertThrows(BusinessException.class,
                () -> walletClient.findWallet(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void reportsWalletServiceUnavailableAsServiceUnavailable() {
        when(walletFeignApi.getWallet(USER_ID))
                .thenThrow(feignError(503, "Load balancer does not contain an instance for the service wallet-service"));

        var exception = assertThrows(BusinessException.class,
                () -> walletClient.findWallet(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void reportsOtherWalletErrorsAsBadGateway() {
        when(walletFeignApi.getWallet(USER_ID)).thenThrow(feignError(500, null));

        var exception = assertThrows(BusinessException.class,
                () -> walletClient.findWallet(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @Test
    void reportsUnexpectedResponseBodyAsBadGateway() {
        when(walletFeignApi.getWallet(USER_ID))
                .thenReturn(new ApiResponse<>(false, null, ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)))
                .thenReturn(ApiResponse.success(null))
                .thenReturn(ApiResponse.success(new WalletFeignApi.WalletResponse(null, "ACTIVE")));

        for (int i = 0; i < 3; i++) {
            var exception = assertThrows(BusinessException.class,
                    () -> walletClient.findWallet(USER_ID));
            assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
        }
    }

    private static FeignException feignError(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "http://wallet-service/internal/v1/wallets",
                Map.of(), null, UTF_8, null);
        Response.Builder builder = Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .headers(Map.of());
        if (body != null) {
            builder.body(body, UTF_8);
        }
        return FeignException.errorStatus("WalletFeignApi#getWallet(UUID)", builder.build());
    }
}
