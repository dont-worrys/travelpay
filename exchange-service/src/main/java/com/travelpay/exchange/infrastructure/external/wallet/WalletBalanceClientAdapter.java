package com.travelpay.exchange.infrastructure.external.wallet;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import feign.FeignException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 지갑 서비스의 내부 잔액 API 응답을 환전의 지갑 잔액으로 바꿉니다. 응답 본문은 예외 메시지·로그에 싣지 않습니다.
 * - 2xx: 지갑 서비스의 상태 문자열은 ACTIVE 여부로만 바꾸고, 환전이 지원하지 않는 통화는 건너뜁니다.
 * - 404 WALLET_NOT_FOUND: 지갑 없음 (empty)
 * - 그 밖의 4xx·5xx·타임아웃·연결 실패·형식 이상: WalletBalanceFetchException
 * 실패를 공통 오류(FeignErrors.toCommonException)로 바꾸지 않는 이유: 잔액을 못 가져와도 한도 조회는 한도만 응답하고,
 * 환전 신청은 지갑 서비스 장애 오류로 응답하는 등 부르는 쪽마다 처리가 달라 이 예외 하나로 알립니다.
 */
@Component
@RequiredArgsConstructor
public class WalletBalanceClientAdapter implements WalletBalanceReader {

    private static final String ACTIVE = "ACTIVE";
    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";

    private final WalletFeignClient feignClient;

    /**
     * 회원의 지갑 잔액을 조회합니다. 지갑이 없으면 empty입니다.
     *
     * @throws WalletBalanceFetchException 호출이 실패했거나 응답을 해석하지 못했을 때
     */
    @Override
    public Optional<WalletBalances> read(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        ApiResponse<WalletBalancesResponse> response;
        try {
            response = feignClient.getBalances(userId);
        } catch (DecodeException e) {
            throw unreadableResponse("JSON을 해석하지 못했습니다.");
        } catch (FeignException e) {
            // 경로가 틀린 404와 구분하려고, 지갑 서비스의 지갑 없음 코드일 때만 지갑이 없다고 봅니다.
            if (e.status() == 404 && FeignErrors.hasErrorCode(e, WALLET_NOT_FOUND)) {
                return Optional.empty();
            }
            // 타임아웃·연결 실패는 응답이 없어 status가 음수입니다.
            throw callFailed(e.status() < 0 ? "응답 없음" : "HTTP " + e.status());
        }
        if (response == null || response.data() == null) {
            throw unreadableResponse("본문이 비어 있습니다.");
        }
        return Optional.of(toWalletBalances(response.data()));
    }

    /**
     * 응답의 잔액 목록을 통화별 계정으로 바꿉니다.
     * 필수 값이 비었거나, 잔액이 음수이거나, 같은 통화가 두 번 오면 해석 실패입니다.
     */
    private static WalletBalances toWalletBalances(WalletBalancesResponse data) {
        if (data.walletId() == null || data.balances() == null) {
            throw unreadableResponse("walletId, balances는 비어 있을 수 없습니다.");
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

    private static WalletBalanceFetchException callFailed(String reason) {
        return new WalletBalanceFetchException("지갑 잔액 API 호출 실패: " + reason);
    }

    private static WalletBalanceFetchException unreadableResponse(String reason) {
        return new WalletBalanceFetchException("지갑 잔액 응답 해석 실패: " + reason);
    }
}
