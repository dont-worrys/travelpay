package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.wallet.application.wallet.LedgerAccountClient;
import com.travelpay.wallet.application.wallet.LedgerResponseException;
import com.travelpay.wallet.application.wallet.dto.LedgerAccount;
import com.travelpay.wallet.domain.Currency;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 원장 응답 해석. 원장 응답 내용은 예외 메시지·응답에 싣지 않는다.
 * - 2xx: 지원 통화가 하나씩 모두 있는 올바른 data만 사용
 * - 404 + LEDGER_ACCOUNT_NOT_FOUND (조회만): 계정 없음
 * - 5xx·타임아웃·연결 실패: 503 (같은 요청 재시도로 풀릴 수 있음)
 * - 그 밖의 4xx(경로 없음 404 포함)·형식 이상: LedgerResponseException(500, 재시도로 풀리지 않음)
 */
@Component
@RequiredArgsConstructor
public class LedgerAccountClientAdapter implements LedgerAccountClient {

    private static final String ACCOUNT_NOT_FOUND = "LEDGER_ACCOUNT_NOT_FOUND";

    private final LedgerFeignClient feignClient;
    private final JsonMapper jsonMapper;

    @Override
    public List<LedgerAccount> openAccounts(UUID walletId) {
        LedgerApiResponse<List<LedgerAccountResponse>> response;
        try {
            response = feignClient.openWalletAccounts(new OpenLedgerAccountsRequest(walletId));
        } catch (FeignException e) {
            throw classify(e);
        }
        return toAccounts(response);
    }

    @Override
    public Optional<List<LedgerAccount>> findAccounts(UUID walletId) {
        LedgerApiResponse<List<LedgerAccountResponse>> response;
        try {
            response = feignClient.getWalletAccounts(walletId);
        } catch (FeignException e) {
            if (isAccountNotFound(e)) {
                return Optional.empty();
            }
            throw classify(e);
        }
        return Optional.of(toAccounts(response));
    }

    private RuntimeException classify(FeignException e) {
        if (e instanceof RetryableException || e.status() >= 500) {
            return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, e);
        }
        return new LedgerResponseException(e);
    }

    private boolean isAccountNotFound(FeignException e) {
        if (e.status() != 404) {
            return false;
        }
        try {
            LedgerApiResponse<?> body = jsonMapper.readValue(e.contentUTF8(), LedgerApiResponse.class);
            return body != null && !body.success() && body.error() != null
                    && ACCOUNT_NOT_FOUND.equals(body.error().code());
        } catch (JacksonException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private List<LedgerAccount> toAccounts(LedgerApiResponse<List<LedgerAccountResponse>> response) {
        if (response == null || !response.success() || response.data() == null) {
            throw new LedgerResponseException();
        }
        List<LedgerAccount> accounts;
        try {
            accounts = response.data().stream()
                    .map(LedgerAccountClientAdapter::toAccount)
                    .sorted(Comparator.comparing(LedgerAccount::currency))
                    .toList();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new LedgerResponseException(e);
        }
        long distinct = accounts.stream().map(LedgerAccount::currency).distinct().count();
        if (accounts.size() != Currency.values().length || distinct != Currency.values().length) {
            throw new LedgerResponseException();
        }
        return accounts;
    }

    private static LedgerAccount toAccount(LedgerAccountResponse response) {
        if (response == null || response.balance() == null) {
            throw new IllegalArgumentException("원장 계정 응답 값이 비어 있습니다.");
        }
        return new LedgerAccount(Currency.valueOf(response.currency()), response.accountId(), response.balance());
    }
}
