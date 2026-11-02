package com.travelpay.payment.infra.client.ledger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.domain.payment.CurrencyCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import com.travelpay.payment.support.FeignFailures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantLedgerFeignClientTest {

    private static final UUID WALLET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private LedgerFeignApi ledgerFeignApi;
    private MerchantLedgerFeignClient ledgerClient;

    @BeforeEach
    void setUp() {
        ledgerFeignApi = mock(LedgerFeignApi.class);
        ledgerClient = new MerchantLedgerFeignClient(ledgerFeignApi);
    }

    @Test
    void acceptsResponseWithMerchantCurrencyAccount() {
        when(ledgerFeignApi.openAccounts(new LedgerFeignApi.OpenAccountsRequest(WALLET_ID)))
                .thenReturn(ApiResponse.success(List.of(account("KRW"), account("JPY"), account("CNY"))));

        assertDoesNotThrow(() -> ledgerClient.openAccounts(WALLET_ID, CurrencyCode.JPY));
    }

    @Test
    void reportsResponseWithoutMerchantCurrencyAsBadGateway() {
        when(ledgerFeignApi.openAccounts(any()))
                .thenReturn(ApiResponse.success(List.of(account("KRW"))));

        assertErrorCode(CommonErrorCode.BAD_GATEWAY, CurrencyCode.JPY);
    }

    @Test
    void reportsEmptyDataAsBadGateway() {
        when(ledgerFeignApi.openAccounts(any())).thenReturn(ApiResponse.success(null));

        assertErrorCode(CommonErrorCode.BAD_GATEWAY, CurrencyCode.KRW);
    }

    @Test
    void reportsConnectionProblemsAsServiceUnavailable() {
        when(ledgerFeignApi.openAccounts(any())).thenThrow(FeignFailures.connectionRefused());

        assertErrorCode(CommonErrorCode.SERVICE_UNAVAILABLE, CurrencyCode.KRW);
    }

    @Test
    void reportsTimeoutAsGatewayTimeout() {
        when(ledgerFeignApi.openAccounts(any())).thenThrow(FeignFailures.readTimeout());

        assertErrorCode(CommonErrorCode.GATEWAY_TIMEOUT, CurrencyCode.KRW);
    }

    @Test
    void reportsLedgerServiceUnavailableAsServiceUnavailable() {
        when(ledgerFeignApi.openAccounts(any())).thenThrow(feignError(503));

        assertErrorCode(CommonErrorCode.SERVICE_UNAVAILABLE, CurrencyCode.KRW);
    }

    @Test
    void reportsLedgerGatewayTimeoutAsGatewayTimeout() {
        when(ledgerFeignApi.openAccounts(any())).thenThrow(feignError(504));

        assertErrorCode(CommonErrorCode.GATEWAY_TIMEOUT, CurrencyCode.KRW);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 502})
    void reportsOtherErrorsAsBadGateway(int status) {
        when(ledgerFeignApi.openAccounts(any())).thenThrow(feignError(status));

        assertErrorCode(CommonErrorCode.BAD_GATEWAY, CurrencyCode.KRW);
    }

    @Test
    void returnsBalanceOfMerchantCurrencyAccount() {
        when(ledgerFeignApi.getAccounts(WALLET_ID))
                .thenReturn(ApiResponse.success(List.of(account("KRW", 15000L), account("JPY", 300L))));

        assertEquals(300L, ledgerClient.getBalance(WALLET_ID, CurrencyCode.JPY));
    }

    @Test
    void reportsBalanceResponseWithoutMerchantCurrencyAsBadGateway() {
        when(ledgerFeignApi.getAccounts(WALLET_ID)).thenReturn(ApiResponse.success(List.of(account("KRW", 0L))));

        assertBalanceErrorCode(CommonErrorCode.BAD_GATEWAY, CurrencyCode.JPY);
    }

    @Test
    void reportsBalanceResponseWithoutBalanceAsBadGateway() {
        when(ledgerFeignApi.getAccounts(WALLET_ID)).thenReturn(ApiResponse.success(List.of(account("KRW", null))));

        assertBalanceErrorCode(CommonErrorCode.BAD_GATEWAY, CurrencyCode.KRW);
    }

    @Test
    void reportsEmptyBalanceDataAsBadGateway() {
        when(ledgerFeignApi.getAccounts(WALLET_ID)).thenReturn(ApiResponse.success(null));

        assertBalanceErrorCode(CommonErrorCode.BAD_GATEWAY, CurrencyCode.KRW);
    }

    @Test
    void reportsBalanceConnectionProblemsAsServiceUnavailable() {
        when(ledgerFeignApi.getAccounts(WALLET_ID)).thenThrow(FeignFailures.connectionRefused());

        assertBalanceErrorCode(CommonErrorCode.SERVICE_UNAVAILABLE, CurrencyCode.KRW);
    }

    @Test
    void reportsBalanceTimeoutAsGatewayTimeout() {
        when(ledgerFeignApi.getAccounts(WALLET_ID)).thenThrow(FeignFailures.readTimeout());

        assertBalanceErrorCode(CommonErrorCode.GATEWAY_TIMEOUT, CurrencyCode.KRW);
    }

    @Test
    void reportsBalanceLedgerServiceUnavailableAsServiceUnavailable() {
        when(ledgerFeignApi.getAccounts(WALLET_ID)).thenThrow(feignError(503));

        assertBalanceErrorCode(CommonErrorCode.SERVICE_UNAVAILABLE, CurrencyCode.KRW);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 502})
    void reportsBalanceOtherErrorsAsBadGateway(int status) {
        when(ledgerFeignApi.getAccounts(WALLET_ID)).thenThrow(feignError(status));

        assertBalanceErrorCode(CommonErrorCode.BAD_GATEWAY, CurrencyCode.KRW);
    }

    private void assertErrorCode(CommonErrorCode expected, CurrencyCode currency) {
        var exception = assertThrows(BusinessException.class, () -> ledgerClient.openAccounts(WALLET_ID, currency));
        assertSame(expected, exception.getErrorCode());
    }

    private void assertBalanceErrorCode(CommonErrorCode expected, CurrencyCode currency) {
        var exception = assertThrows(BusinessException.class, () -> ledgerClient.getBalance(WALLET_ID, currency));
        assertSame(expected, exception.getErrorCode());
    }

    private static LedgerFeignApi.AccountResponse account(String currency) {
        return account(currency, 0L);
    }

    private static LedgerFeignApi.AccountResponse account(String currency, Long balance) {
        return new LedgerFeignApi.AccountResponse(UUID.randomUUID(), currency, balance);
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "/internal/v1/ledger/accounts",
                Map.of(), null, UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .request(request)
                .body("", UTF_8)
                .build();
        return FeignException.errorStatus("LedgerFeignApi#openAccounts", response);
    }
}
