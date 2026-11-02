package com.travelpay.exchange.infrastructure.external.wallet;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletBalanceClientAdapterTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Request REQUEST = Request.create(Request.HttpMethod.GET,
            "http://wallet-service/internal/v1/wallets/balances?userId=" + USER_ID,
            Map.of(), null, StandardCharsets.UTF_8, null);

    private final WalletFeignClient feignClient = mock(WalletFeignClient.class);
    private final WalletBalanceClientAdapter adapter = new WalletBalanceClientAdapter(feignClient);

    /** PR #47 응답 JSON이 공통 ApiResponse와 data record로 해석되는지 확인합니다. Feign도 Jackson으로 같은 타입을 만듭니다. */
    @Test
    void readsWalletServiceJsonIntoCommonApiResponse() {
        ApiResponse<WalletBalancesResponse> response = JsonMapper.builder().build().readValue("""
                {"success":true,"data":{"walletId":"%s","status":"ACTIVE","balances":[
                  {"currency":"CNY","balance":3520,"status":"SUSPENDED"}
                ]},"error":null}
                """.formatted(WALLET_ID), new TypeReference<ApiResponse<WalletBalancesResponse>>() {
        });

        assertEquals(ApiResponse.success(new WalletBalancesResponse(WALLET_ID, "ACTIVE",
                List.of(new WalletBalancesResponse.Balance("CNY", 3520L, "SUSPENDED")))), response);
    }

    /** 잔액은 최소 단위 정수이고, 상태는 ACTIVE 여부로만 봅니다. */
    @Test
    void mapsBalancesAndStatuses() {
        respondWith("ACTIVE",
                balance("KRW", 500_000L, "ACTIVE"),
                balance("JPY", 0L, "ACTIVE"),
                balance("CNY", 3520L, "SUSPENDED"));

        WalletBalances wallet = adapter.read(USER_ID).orElseThrow();

        assertEquals(WALLET_ID, wallet.walletId());
        assertTrue(wallet.active());
        assertEquals(new WalletBalances.Account(new Money(Currency.KRW, 500_000), true),
                wallet.account(Currency.KRW).orElseThrow());
        assertEquals(new WalletBalances.Account(new Money(Currency.CNY, 3520), false),
                wallet.account(Currency.CNY).orElseThrow());
    }

    /** 개설 중(PENDING) 지갑은 통화 목록이 비어 있고, 환전이 모르는 통화는 건너뜁니다. */
    @Test
    void skipsUnsupportedCurrencyAndTreatsOtherStatusAsInactive() {
        respondWith("PENDING", balance("USD", 100L, "ACTIVE"));

        WalletBalances wallet = adapter.read(USER_ID).orElseThrow();

        assertFalse(wallet.active());
        assertTrue(wallet.accounts().isEmpty());
    }

    @Test
    void returnsEmptyWhenWalletDoesNotExist() {
        when(feignClient.getBalances(USER_ID)).thenThrow(errorResponse(404, """
                {"success":false,"data":null,"error":{"code":"WALLET_NOT_FOUND","message":"지갑을 찾을 수 없습니다."}}
                """));

        assertEquals(Optional.empty(), adapter.read(USER_ID));
    }

    /** 경로가 틀린 404 같은 경우를 지갑 없음으로 오해하지 않습니다. */
    @Test
    void otherNotFoundIsFailure() {
        when(feignClient.getBalances(USER_ID)).thenThrow(errorResponse(404, """
                {"success":false,"data":null,"error":{"code":"COMMON_NOT_FOUND","message":"없음"}}
                """));

        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));
    }

    @Test
    void notFoundWithoutBodyIsFailure() {
        when(feignClient.getBalances(USER_ID)).thenThrow(errorResponse(404, ""));

        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));
    }

    /** 응답 본문은 예외 메시지에 싣지 않고 상태 코드만 남깁니다. */
    @Test
    void serverErrorIsFailureWithoutResponseBody() {
        when(feignClient.getBalances(USER_ID)).thenThrow(errorResponse(500, "{\"secret\":\"내부 정보\"}"));

        WalletBalanceFetchException exception =
                assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));
        assertEquals("지갑 잔액 API 호출 실패: HTTP 500", exception.getMessage());
    }

    @Test
    void timeoutIsFailure() {
        when(feignClient.getBalances(USER_ID)).thenThrow(
                new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, (Long) null, REQUEST));

        WalletBalanceFetchException exception =
                assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));
        assertEquals("지갑 잔액 API 호출 실패: 응답 없음", exception.getMessage());
    }

    @Test
    void undecodableBodyIsFailure() {
        when(feignClient.getBalances(USER_ID)).thenThrow(new DecodeException(200, "bad json", REQUEST));

        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));
    }

    @Test
    void missingRequiredFieldsAreFailure() {
        when(feignClient.getBalances(USER_ID)).thenReturn(null);
        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));

        when(feignClient.getBalances(USER_ID)).thenReturn(ApiResponse.success(null));
        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));

        when(feignClient.getBalances(USER_ID)).thenReturn(ApiResponse.success(
                new WalletBalancesResponse(WALLET_ID, "ACTIVE", null)));
        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));
    }

    @Test
    void negativeOrMissingOrDuplicatedBalanceIsFailure() {
        respondWith("ACTIVE", balance("KRW", -1L, "ACTIVE"));
        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));

        respondWith("ACTIVE", balance("KRW", null, "ACTIVE"));
        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));

        respondWith("ACTIVE", balance("KRW", 1L, "ACTIVE"), balance("KRW", 2L, "ACTIVE"));
        assertThrows(WalletBalanceFetchException.class, () -> adapter.read(USER_ID));
    }

    private void respondWith(String walletStatus, WalletBalancesResponse.Balance... balances) {
        when(feignClient.getBalances(USER_ID)).thenReturn(ApiResponse.success(
                new WalletBalancesResponse(WALLET_ID, walletStatus, List.of(balances))));
    }

    private static WalletBalancesResponse.Balance balance(String currency, Long balance, String status) {
        return new WalletBalancesResponse.Balance(currency, balance, status);
    }

    /** Feign이 2xx가 아닌 응답을 받았을 때 던지는 예외와 같은 것을 만듭니다. */
    private static FeignException errorResponse(int status, String body) {
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .request(REQUEST)
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("WalletFeignClient#getBalances(UUID)", response);
    }
}
