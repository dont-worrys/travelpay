package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 충전 복구의 DB 트랜잭션 단계. 선점 트랜잭션이 커밋된 뒤 실행기를 부르도록
 * ChargeRecoveryScheduler(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다
 */
@Component
@RequiredArgsConstructor
public class ChargeRecoverySteps {

    private final ChargeRequestRepository chargeRequestRepository;

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED,
            timeout = ChargeSagaSteps.TX_TIMEOUT_SECONDS)
    public List<ChargeRecoveryTarget> findTargets(Collection<ChargeStatus> statuses, int limit) {
        return chargeRequestRepository.findRecoveryTargets(statuses, limit);
    }

    /** 처리 중 상태이면서 createdBefore보다 먼저 생성된 충전 수 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED,
            timeout = ChargeSagaSteps.TX_TIMEOUT_SECONDS)
    public long countStuck(Instant createdBefore) {
        return chargeRequestRepository.countByStatusInAndCreatedBefore(ChargeRecoveryGroup.allStatuses(),
                createdBefore);
    }

    /** 조회 이후 아무도 바꾸지 않았으면 선점하고 true, 다른 실행기·스케줄러가 먼저 바꿨으면 false */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = ChargeSagaSteps.TX_TIMEOUT_SECONDS)
    public boolean claim(ChargeRecoveryTarget target) {
        return chargeRequestRepository.claimForRecovery(target.id(), target.status(), target.updatedAt()) == 1;
    }
}
