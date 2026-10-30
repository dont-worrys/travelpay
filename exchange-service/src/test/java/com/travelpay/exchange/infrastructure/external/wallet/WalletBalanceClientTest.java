package com.travelpay.exchange.infrastructure.external.wallet;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WalletBalanceClientTest {

    private static final String BASE_URL = "http://wallet-service/internal/v1/wallets/balances";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private MockRestServiceServer server;
    private WalletBalanceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WalletBalanceClient(builder.build());
    }

    /** PR #47 응답 형식입니다. 잔액은 최소 단위 정수이고, 상태는 ACTIVE 여부로만 봅니다. */
    @Test
    void mapsBalancesAndStatuses() {
        respondWith("""
                {"success":true,"data":{"walletId":"%s","status":"ACTIVE","balances":[
                  {"currency":"KRW","balance":500000,"status":"ACTIVE"},
                  {"currency":"JPY","balance":0,"status":"ACTIVE"},
                  {"currency":"CNY","balance":3520,"status":"SUSPENDED"}
                ]},"error":null}
                """.formatted(WALLET_ID));

        WalletBalances wallet = client.read(USER_ID).orElseThrow();

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
        respondWith("""
                {"success":true,"data":{"walletId":"%s","status":"PENDING","balances":[
                  {"currency":"USD","balance":100,"status":"ACTIVE"}
                ]},"error":null}
                """.formatted(WALLET_ID));

        WalletBalances wallet = client.read(USER_ID).orElseThrow();

        assertFalse(wallet.active());
        assertTrue(wallet.accounts().isEmpty());
    }

    @Test
    void returnsEmptyWhenWalletDoesNotExist() {
        server.expect(requestTo(BASE_URL + "?userId=" + USER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"success":false,"data":null,"error":{"code":"WALLET_NOT_FOUND","message":"지갑을 찾을 수 없습니다."}}
                        """));

        assertEquals(Optional.empty(), client.read(USER_ID));
    }

    /** 경로가 틀린 404 같은 경우를 지갑 없음으로 오해하지 않습니다. */
    @Test
    void otherNotFoundIsFailure() {
        server.expect(requestTo(BASE_URL + "?userId=" + USER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"success":false,"data":null,"error":{"code":"COMMON_NOT_FOUND","message":"없음"}}
                        """));

        assertThrows(WalletBalanceFetchException.class, () -> client.read(USER_ID));
    }

    @Test
    void serverErrorIsFailure() {
        server.expect(requestTo(BASE_URL + "?userId=" + USER_ID)).andRespond(withServerError());

        assertThrows(WalletBalanceFetchException.class, () -> client.read(USER_ID));
    }

    @Test
    void timeoutIsFailure() {
        server.expect(requestTo(BASE_URL + "?userId=" + USER_ID)).andRespond(withException(new IOException("timeout")));

        assertThrows(WalletBalanceFetchException.class, () -> client.read(USER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "{\"success\":true,\"data\":null}",
            "{\"data\":{\"walletId\":\"00000000-0000-0000-0000-0000000000aa\",\"status\":\"ACTIVE\"}}",
            "{\"data\":{\"walletId\":\"00000000-0000-0000-0000-0000000000aa\",\"status\":\"ACTIVE\",\"balances\":[{\"currency\":\"KRW\",\"balance\":-1,\"status\":\"ACTIVE\"}]}}",
            "{\"data\":{\"walletId\":\"00000000-0000-0000-0000-0000000000aa\",\"status\":\"ACTIVE\",\"balances\":[{\"currency\":\"KRW\",\"status\":\"ACTIVE\"}]}}",
            "{\"data\":{\"walletId\":\"00000000-0000-0000-0000-0000000000aa\",\"status\":\"ACTIVE\",\"balances\":[{\"currency\":\"KRW\",\"balance\":1,\"status\":\"ACTIVE\"},{\"currency\":\"KRW\",\"balance\":2,\"status\":\"ACTIVE\"}]}}"
    })
    void unreadableResponseIsFailure(String body) {
        respondWith(body);

        assertThrows(WalletBalanceFetchException.class, () -> client.read(USER_ID));
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE_URL + "?userId=" + USER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
