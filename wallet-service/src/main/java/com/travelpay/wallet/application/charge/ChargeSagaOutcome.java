package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * SAGA 실행 한 번에서 외부 호출(은행·원장)이 어떻게 끝났는지. 복구 스케줄러가 단계 묶음별 장애를 판단하는 데 쓴다.
 * answeredAt: 확실한 응답(성공·업무 거절·응답 이상)을 받은 단계 상태
 * unknownAt: 결과 모름으로 멈춘 단계 상태 (실행은 결과 모름에서 멈추므로 최대 하나이고, 항상 마지막 외부 호출)
 */
public record ChargeSagaOutcome(Set<ChargeStatus> answeredAt, ChargeStatus unknownAt) {

    /** 외부 호출 없이 끝남 (대상 없음·종료 상태·다른 실행기가 먼저 진행 등) */
    public static final ChargeSagaOutcome NONE = new ChargeSagaOutcome(Set.of(), null);

    public ChargeSagaOutcome {
        answeredAt = Set.copyOf(Objects.requireNonNull(answeredAt, "answeredAt"));
    }

    public Optional<ChargeStatus> unknownStatus() {
        return Optional.ofNullable(unknownAt);
    }
}
