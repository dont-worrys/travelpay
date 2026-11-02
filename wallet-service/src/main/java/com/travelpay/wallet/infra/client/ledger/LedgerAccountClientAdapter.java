package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.wallet.LedgerAccountClient;
import com.travelpay.wallet.application.wallet.LedgerResponseException;
import com.travelpay.wallet.application.wallet.dto.LedgerAccount;
import com.travelpay.wallet.domain.Currency;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 원장 응답 해석. 원장 응답 내용은 예외 메시지·응답·로그에 싣지 않는다.
 * - 2xx: 지원 통화가 하나씩 모두 있는 올바른 data만 사용 (아니면 LedgerResponseException 500)
 * - 404 + LEDGER_ACCOUNT_NOT_FOUND (조회만): 계정 없음
 * - 그 밖의 실패: FeignErrors.toCommonException (시간 초과·504 → 504, 연결 실패·503 → 503, 나머지 502).
 *   처리하지 않은 4xx와 2xx 본문 해석 실패는 ERROR로 남긴다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerAccountClientAdapter implements LedgerAccountClient {

    private static final String ACCOUNT_NOT_FOUND = "LEDGER_ACCOUNT_NOT_FOUND";

    private final LedgerFeignClient feignClient;

    @Override
    public List<LedgerAccount> openAccounts(UUID walletId) {
        ApiResponse<List<LedgerAccountResponse>> response;
        try {
            response = feignClient.openWalletAccounts(new OpenLedgerAccountsRequest(walletId));
        } catch (FeignException e) {
            throw classify(walletId, e);
        }
        return toAccounts(response);
    }

    @Override
    public Optional<List<LedgerAccount>> findAccounts(UUID walletId) {
        ApiResponse<List<LedgerAccountResponse>> response;
        try {
            response = feignClient.getWalletAccounts(walletId);
        } catch (FeignException e) {
            if (e.status() == 404 && FeignErrors.hasErrorCode(e, ACCOUNT_NOT_FOUND)) {
                return Optional.empty();
            }
            throw classify(walletId, e);
        }
        return Optional.of(toAccounts(response));
    }

    private BusinessException classify(UUID walletId, FeignException e) {
        if (FeignErrors.isRejected(e) || isSuccessStatus(e)) {
            log.error("원장 계정 응답 처리 불가 walletId={} status={} code={}",
                    walletId, e.status(), FeignErrors.errorCode(e).orElse(null));
        }
        return FeignErrors.toCommonException(e);
    }

    /** 2xx인데 Feign 예외면 본문을 해석하지 못한 것이다 */
    private static boolean isSuccessStatus(FeignException e) {
        return e.status() >= 200 && e.status() < 300;
    }

    private List<LedgerAccount> toAccounts(ApiResponse<List<LedgerAccountResponse>> response) {
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
