package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 복구 스케줄러의 장애 감지 단위. 처리 중 상태를 다음에 부를 외부 의존성으로 묶는다.
 * REQUESTED는 바로 BANK_PROCESSING으로 넘어가 출금을 부르므로 은행 묶음이다
 */
public enum ChargeRecoveryGroup {

    BANK("은행", EnumSet.of(ChargeStatus.REQUESTED, ChargeStatus.BANK_PROCESSING, ChargeStatus.REFUND_PROCESSING)),
    LEDGER("원장", EnumSet.of(ChargeStatus.CREDIT_PROCESSING));

    private final String label;
    private final Set<ChargeStatus> statuses;

    ChargeRecoveryGroup(String label, Set<ChargeStatus> statuses) {
        this.label = label;
        this.statuses = Set.copyOf(statuses);
    }

    public String label() {
        return label;
    }

    public Set<ChargeStatus> statuses() {
        return statuses;
    }

    /** 처리 중 상태가 아니면 IllegalArgumentException */
    public static ChargeRecoveryGroup of(ChargeStatus status) {
        return Arrays.stream(values())
                .filter(group -> group.statuses.contains(status))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("복구 대상 상태가 아님: " + status));
    }

    /** 모든 묶음의 상태 = 처리 중 상태 전체 */
    public static Set<ChargeStatus> allStatuses() {
        Set<ChargeStatus> all = EnumSet.noneOf(ChargeStatus.class);
        for (ChargeRecoveryGroup group : values()) {
            all.addAll(group.statuses);
        }
        return Set.copyOf(all);
    }
}
