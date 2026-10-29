package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.application.charge.dto.ChargeRequestedPayload;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.charge.ChargeStatusHistory;
import com.travelpay.wallet.domain.charge.ChargeStatusHistoryRepository;
import com.travelpay.wallet.domain.outbox.AggregateType;
import com.travelpay.wallet.domain.outbox.OutboxEvent;
import com.travelpay.wallet.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * 충전 접수·조회의 DB 트랜잭션 단계. 회원 서비스 호출이 트랜잭션 안에 들어가지 않도록
 * ChargeService(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다.
 */
@Component
@RequiredArgsConstructor
public class ChargeTransactionSteps {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    static final int TX_TIMEOUT_SECONDS = 3;

    static final String CHARGE_REQUESTED_EVENT = "wallet.charge.requested";

    private final ChargeRequestRepository chargeRequestRepository;
    private final ChargeStatusHistoryRepository historyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    /** 동시 요청 재조회에서 다른 트랜잭션이 커밋한 행을 봐야 하므로 READ COMMITTED 전제 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ChargeRequest> findByIdempotencyKey(UUID userId, UUID idempotencyKey) {
        return chargeRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ChargeRequest> findById(UUID chargeId) {
        return chargeRequestRepository.findById(chargeId);
    }

    /**
     * 충전(REQUESTED) + 상태 이력(null → REQUESTED) + Outbox를 한 트랜잭션으로 저장한다.
     * 같은 멱등 키가 먼저 저장됐으면 DuplicateChargeRequestException으로 롤백된다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public ChargeRequest accept(ChargeRequest chargeRequest) {
        ChargeRequest saved = chargeRequestRepository.save(chargeRequest);
        historyRepository.save(ChargeStatusHistory.record(saved.getId(), null, saved.getStatus(),
                null, null, null));
        String payload = jsonMapper.writeValueAsString(new ChargeRequestedPayload(
                saved.getId(), saved.getWalletId(), saved.getUserId(), saved.getAmount(), saved.getCurrency(),
                saved.getBankAccount().getBankCode(), saved.getBankRequestId()));
        outboxEventRepository.save(OutboxEvent.create(AggregateType.CHARGE, saved.getId(),
                CHARGE_REQUESTED_EVENT, payload));
        return saved;
    }
}
