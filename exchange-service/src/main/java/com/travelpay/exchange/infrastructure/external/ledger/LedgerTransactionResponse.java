package com.travelpay.exchange.infrastructure.external.ledger;

import java.util.UUID;

/**
 * 원장 거래 등록 응답(공통 ApiResponse 형식)입니다. Feign이 Jackson으로 만들므로 public이어야 합니다.
 * 성공이면 data, 실패면 error가 있습니다.
 */
public record LedgerTransactionResponse(boolean success, Data data, Error error) {

    /** @param status POSTED 또는 REJECTED. 거절은 422 오류로 오므로 성공 응답은 POSTED여야 합니다. */
    public record Data(UUID transactionId, String status) {
    }

    /** @param code 거절이면 LEDGER_REJECTED_*, 같은 reference에 다른 내용이면 LEDGER_REFERENCE_CONFLICT */
    public record Error(String code) {
    }
}
