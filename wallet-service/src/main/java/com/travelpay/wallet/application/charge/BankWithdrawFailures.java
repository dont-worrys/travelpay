package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;

import java.util.Map;

/** 은행 출금 실패 코드 → 충전 실패 코드. SAGA 출금 단계와 운영자 보정(CONFIRM_FAILED)이 같은 매핑을 쓴다 */
public final class BankWithdrawFailures {

    private static final Map<String, ChargeFailureCode> MAPPED = Map.of(
            "ACCOUNT_NOT_FOUND", ChargeFailureCode.BANK_ACCOUNT_NOT_FOUND,
            "ACCOUNT_SUSPENDED", ChargeFailureCode.BANK_ACCOUNT_SUSPENDED,
            "HOLDER_MISMATCH", ChargeFailureCode.BANK_HOLDER_MISMATCH,
            "INSUFFICIENT_BALANCE", ChargeFailureCode.BANK_INSUFFICIENT_BALANCE);

    private BankWithdrawFailures() {
    }

    /** 매핑에 있는 은행 코드인지 (없는 코드는 BANK_REJECTED로 보고 원래 코드를 last_error에 남긴다) */
    public static boolean isMapped(String bankFailureCode) {
        return bankFailureCode != null && MAPPED.containsKey(bankFailureCode);
    }

    /** 매핑에 없는 코드(null 포함)는 BANK_REJECTED */
    public static ChargeFailureCode toChargeFailureCode(String bankFailureCode) {
        return isMapped(bankFailureCode) ? MAPPED.get(bankFailureCode) : ChargeFailureCode.BANK_REJECTED;
    }
}
