package com.travelpay.payment.infra.client.ledger;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.merchant.client.MerchantLedgerClient;
import com.travelpay.payment.domain.payment.CurrencyCode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantLedgerFeignClient implements MerchantLedgerClient {

    private final LedgerFeignApi ledgerFeignApi;

    @Override
    public void openAccounts(UUID walletId, CurrencyCode currency) {
        ApiResponse<List<LedgerFeignApi.AccountResponse>> response = call("생성", walletId,
                () -> ledgerFeignApi.openAccounts(new LedgerFeignApi.OpenAccountsRequest(walletId)));

        if (response == null || !response.success() || response.data() == null
                || response.data().stream().noneMatch(account -> isAccountOf(account, currency))) {
            log.error("원장 계정 생성 응답 형식 이상 walletId={} currency={}", walletId, currency);
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
    }

    @Override
    public long getBalance(UUID walletId, CurrencyCode currency) {
        ApiResponse<List<LedgerFeignApi.AccountResponse>> response = call("조회", walletId,
                () -> ledgerFeignApi.getAccounts(walletId));

        if (response == null || !response.success() || response.data() == null) {
            log.error("원장 계정 조회 응답 형식 이상 walletId={} currency={}", walletId, currency);
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return response.data().stream()
                .filter(account -> isAccountOf(account, currency) && account.balance() != null)
                .map(LedgerFeignApi.AccountResponse::balance)
                .findFirst()
                .orElseThrow(() -> {
                    log.error("원장 계정 조회 응답에 가맹점 통화 계정 없음 walletId={} currency={}", walletId, currency);
                    return new BusinessException(CommonErrorCode.BAD_GATEWAY);
                });
    }

    /** 원장 계정 호출 실패는 따로 처리할 오류 코드가 없어 공통 규칙(시간 초과 504, 연결 실패·503은 503, 그 밖은 502)으로 바꾼다 */
    private <T> T call(String action, UUID walletId, Supplier<T> request) {
        try {
            return request.get();
        } catch (FeignException exception) {
            log.error("원장 계정 {} 실패 walletId={} status={}", action, walletId, exception.status());
            throw FeignErrors.toCommonException(exception);
        }
    }

    private static boolean isAccountOf(LedgerFeignApi.AccountResponse account, CurrencyCode currency) {
        return account != null && account.accountId() != null && currency.name().equals(account.currency());
    }
}
