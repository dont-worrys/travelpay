package com.travelpay.wallet.application.wallet;

import com.travelpay.wallet.application.wallet.dto.LedgerAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 원장 지갑 계정 호출. 반환 목록은 지원 통화가 하나씩 모두 있고 통화 순(KRW, JPY, CNY)이다.
 * 5xx·타임아웃·연결 실패는 BusinessException(SERVICE_UNAVAILABLE),
 * 그 밖의 4xx·응답 형식 이상은 LedgerResponseException(500)으로 알린다.
 */
public interface LedgerAccountClient {

    /** 멱등. 이미 있는 계정은 그대로 돌려받는다 */
    List<LedgerAccount> openAccounts(UUID walletId);

    /** 원장이 "계정 없음"(LEDGER_ACCOUNT_NOT_FOUND)으로 답한 경우에만 empty */
    Optional<List<LedgerAccount>> findAccounts(UUID walletId);
}
