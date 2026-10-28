package com.travelpay.payment.transfer.client;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferWalletReaderTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID WALLET_ID = UUID.fromString("0f8e6d4c-2b1a-4f9e-8d7c-6b5a4f3e2d1c");

    private WalletClient walletClient;
    private TransferWalletReader walletReader;

    @BeforeEach
    void setUp() {
        walletClient = mock(WalletClient.class);
        walletReader = new TransferWalletReader(walletClient);
    }

    @Test
    void returnsWalletIdOnlyForActiveWallet() {
        when(walletClient.getWallet(USER_ID)).thenReturn(ApiResponse.success(new WalletResponse(WALLET_ID, "ACTIVE")));

        assertEquals(Optional.of(WALLET_ID), walletReader.findActiveWalletId(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUSPENDED", "CLOSED", "UNKNOWN_NEW_STATUS"})
    void treatsNonActiveWalletAsUnavailable(String status) {
        when(walletClient.getWallet(USER_ID)).thenReturn(ApiResponse.success(new WalletResponse(WALLET_ID, status)));

        assertEquals(Optional.empty(), walletReader.findActiveWalletId(USER_ID));
    }

    @Test
    void treatsMissingWalletAsUnavailable() {
        when(walletClient.getWallet(USER_ID)).thenThrow(feignError(404));

        assertEquals(Optional.empty(), walletReader.findActiveWalletId(USER_ID));
    }

    @Test
    void reportsConnectionProblemsAsServiceUnavailable() {
        when(walletClient.getWallet(USER_ID)).thenThrow(mock(RetryableException.class));

        var exception = assertThrows(BusinessException.class, () -> walletReader.findActiveWalletId(USER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void reportsOtherWalletErrorsAsBadGateway() {
        when(walletClient.getWallet(USER_ID)).thenThrow(feignError(500));

        var exception = assertThrows(BusinessException.class, () -> walletReader.findActiveWalletId(USER_ID));

        assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
    }

    @Test
    void reportsUnexpectedResponseBodyAsBadGateway() {
        when(walletClient.getWallet(USER_ID))
                .thenReturn(new ApiResponse<>(false, null, ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)))
                .thenReturn(ApiResponse.success(null))
                .thenReturn(ApiResponse.success(new WalletResponse(null, "ACTIVE")));

        for (int i = 0; i < 3; i++) {
            var exception = assertThrows(BusinessException.class, () -> walletReader.findActiveWalletId(USER_ID));
            assertSame(CommonErrorCode.BAD_GATEWAY, exception.getErrorCode());
        }
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://wallet-service/internal/v1/wallets",
                Map.of(), null, UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("WalletClient#getWallet(UUID)", response);
    }
}
