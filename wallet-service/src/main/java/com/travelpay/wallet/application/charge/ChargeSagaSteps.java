package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.application.charge.ChargeStatusTransitioner.UnknownRecord;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 충전 SAGA의 DB 트랜잭션 단계. 은행·원장 호출이 트랜잭션 안에 들어가지 않도록
 * ChargeSagaExecutor(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다.
 * 다른 실행기가 커밋한 상태를 봐야 하므로 READ COMMITTED 전제
 */
@Component
@RequiredArgsConstructor
public class ChargeSagaSteps {

    /** 상태 변경만 하는 짧은 트랜잭션 상한(초) */
    static final int TX_TIMEOUT_SECONDS = 3;

    private final ChargeRequestRepository chargeRequestRepository;
    private final ChargeStatusTransitioner transitioner;

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ChargeRequest> load(UUID chargeId) {
        return chargeRequestRepository.findById(chargeId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public boolean advance(UUID chargeId, ChargeStatus from, ChargeStatus to,
                           ChargeStepResult result, String traceId) {
        return transitioner.advance(chargeId, from, to, result, traceId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public UnknownRecord recordUnknown(UUID chargeId, ChargeStatus status, String lastError, String traceId) {
        return transitioner.recordUnknown(chargeId, status, lastError, traceId);
    }
}
