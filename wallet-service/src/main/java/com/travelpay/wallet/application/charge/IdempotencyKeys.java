package com.travelpay.wallet.application.charge;

import com.travelpay.common.error.BusinessException;
import com.travelpay.wallet.domain.WalletErrorCode;

import java.util.UUID;

/** Idempotency-Key 헤더 해석. 충전 접수·충전 보정이 같은 규칙을 쓴다 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /** 표준 UUID 표기만 받는다. 없거나 형식 오류면 DB 조회 전에 400 */
    public static UUID parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(WalletErrorCode.CHARGE_INVALID_IDEMPOTENCY_KEY);
        }
        String normalized = value.strip();
        UUID parsed;
        try {
            parsed = UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(WalletErrorCode.CHARGE_INVALID_IDEMPOTENCY_KEY);
        }
        // UUID.fromString이 허용하는 축약형까지 받아들이지 않도록 표준 표기와 비교
        if (!parsed.toString().equalsIgnoreCase(normalized)) {
            throw new BusinessException(WalletErrorCode.CHARGE_INVALID_IDEMPOTENCY_KEY);
        }
        return parsed;
    }
}
