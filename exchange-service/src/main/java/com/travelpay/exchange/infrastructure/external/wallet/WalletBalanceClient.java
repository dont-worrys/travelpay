package com.travelpay.exchange.infrastructure.external.wallet;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지갑 서비스의 내부 잔액 API로 통화별 잔액을 가져옵니다.
 * 지갑 서비스의 상태 문자열은 ACTIVE 여부로만 바꾸고, 환전이 지원하지 않는 통화는 건너뜁니다.
 */
public class WalletBalanceClient implements WalletBalanceReader {

    private static final String ACTIVE = "ACTIVE";
    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    public WalletBalanceClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    @Override
    public Optional<WalletBalances> read(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        String body;
        try {
            body = restClient.get()
                    .uri(builder -> builder.queryParam("userId", userId).build())
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            // 경로가 틀린 404와 구분하려고, 지갑 서비스의 지갑 없음 코드일 때만 지갑이 없다고 봅니다.
            if (isWalletNotFound(e.getResponseBodyAsString())) {
                return Optional.empty();
            }
            throw callFailed("HTTP 404");
        } catch (RestClientResponseException e) {
            // 응답 본문은 로그로 남기지 않고 상태 코드만 남깁니다.
            throw callFailed("HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw callFailed(e.getMessage());
        }
        return Optional.of(toWalletBalances(parse(body)));
    }

    /**
     * 응답의 잔액 목록을 통화별 계정으로 바꿉니다.
     * 필수 값이 비었거나, 잔액이 음수이거나, 같은 통화가 두 번 오면 해석 실패입니다.
     */
    private static WalletBalances toWalletBalances(WalletBalancesResponse response) {
        WalletBalancesResponse.Data data = response.data();
        if (data == null || data.walletId() == null || data.balances() == null) {
            throw unreadableResponse("data, walletId, balances는 비어 있을 수 없습니다.");
        }
        Map<Currency, WalletBalances.Account> accounts = new EnumMap<>(Currency.class);
        for (WalletBalancesResponse.Balance balance : data.balances()) {
            Optional<Currency> currency = Currency.find(balance.currency());
            if (currency.isEmpty()) {
                continue;
            }
            if (balance.balance() == null || balance.balance() < 0) {
                throw unreadableResponse(currency.get() + " 잔액이 올바르지 않습니다: " + balance.balance());
            }
            WalletBalances.Account account = new WalletBalances.Account(
                    new Money(currency.get(), balance.balance()), ACTIVE.equals(balance.status()));
            if (accounts.put(currency.get(), account) != null) {
                throw unreadableResponse(currency.get() + " 잔액이 두 번 왔습니다.");
            }
        }
        return new WalletBalances(data.walletId(), ACTIVE.equals(data.status()), accounts);
    }

    private static boolean isWalletNotFound(String body) {
        try {
            WalletBalancesResponse response = parse(body);
            return response.error() != null && WALLET_NOT_FOUND.equals(response.error().code());
        } catch (WalletBalanceFetchException e) {
            return false;
        }
    }

    private static WalletBalancesResponse parse(String body) {
        if (body == null || body.isBlank()) {
            throw unreadableResponse("본문이 비어 있습니다.");
        }
        try {
            return JSON.readValue(body, WalletBalancesResponse.class);
        } catch (JacksonException e) {
            throw unreadableResponse(e.getOriginalMessage());
        }
    }

    // 5XX, 타임 아웃 및 네트워크 오류
    private static WalletBalanceFetchException callFailed(String reason) {
        return new WalletBalanceFetchException("지갑 잔액 API 호출 실패: " + reason);
    }

    private static WalletBalanceFetchException unreadableResponse(String reason) {
        return new WalletBalanceFetchException("지갑 잔액 응답 해석 실패: " + reason);
    }
}
