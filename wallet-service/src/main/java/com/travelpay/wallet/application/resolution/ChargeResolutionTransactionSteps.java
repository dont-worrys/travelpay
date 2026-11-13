package com.travelpay.wallet.application.resolution;

import com.travelpay.common.error.BusinessException;
import com.travelpay.wallet.application.charge.ChargeStatusTransitioner;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeRequestRepository;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResultRepository;
import com.travelpay.wallet.domain.resolution.ChargeResolution;
import com.travelpay.wallet.domain.resolution.ChargeResolutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 충전 보정의 DB 트랜잭션 단계. 은행·원장 재조회가 트랜잭션 안에 들어가지 않도록
 * ChargeResolutionService(트랜잭션 없음)와 분리해 프록시를 거쳐 호출한다
 */
@Component
@RequiredArgsConstructor
public class ChargeResolutionTransactionSteps {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    static final int TX_TIMEOUT_SECONDS = 3;

    private final ChargeRequestRepository chargeRequestRepository;
    private final ChargeResolutionRepository resolutionRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final ChargeStatusTransitioner transitioner;

    /** 동시 요청 재조회에서 다른 트랜잭션이 커밋한 행을 봐야 하므로 READ COMMITTED 전제 (spring.datasource.hikari.transaction-isolation) */
    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ChargeResolution> findByIdempotencyKey(UUID idempotencyKey) {
        return resolutionRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ChargeRequest> findCharge(UUID chargeId) {
        return chargeRequestRepository.findById(chargeId);
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public Optional<ReconciliationResult> findReconciliationResult(UUID resultId) {
        return reconciliationResultRepository.findById(resultId);
    }

    /**
     * 한 트랜잭션: 충전 행 잠금(MANUAL_REVIEW만) → 보정 기록 저장(멱등 키 UNIQUE) → MANUAL_REVIEW 조건부 UPDATE
     * + 보정 ID를 담은 상태 이력 → 대사 결과 연결.
     * - 같은 키가 먼저 저장됨: DuplicateChargeResolutionException
     * - 잠금 시점에 MANUAL_REVIEW가 아니거나 UPDATE 0건(이미 다른 보정이 반영됨): ResolutionNotAppliedException
     * - 대사 결과가 이미 처리됨: 409
     * 어느 경우든 전부 롤백된다
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public ChargeResolution apply(ChargeResolution resolution, ChargeFailureCode failureCode,
                                  UUID bankTxId, UUID ledgerRef, UUID refundBankTxId) {
        // 충전 행을 먼저 잠가 동시 보정의 순서를 정한다 (뒤 요청은 앞 요청 커밋 후 MANUAL_REVIEW가 아니면 여기서 끝남)
        if (!chargeRequestRepository.lockManualReview(resolution.getChargeId())) {
            throw new ResolutionNotAppliedException();
        }
        ChargeResolution saved = resolutionRepository.save(resolution);
        boolean resolved = transitioner.resolve(saved.getChargeId(), saved.getResultStatus(), failureCode,
                bankTxId, ledgerRef, refundBankTxId,
                saved.getAction().historyReasonCode(), saved.getReason(), saved.getId());
        if (!resolved) {
            throw new ResolutionNotAppliedException();
        }
        if (saved.getReconciliationResultId() != null
                && reconciliationResultRepository.markResolved(saved.getReconciliationResultId(),
                saved.getChargeId(), saved.getId()) == 0) {
            throw new BusinessException(WalletErrorCode.RECONCILIATION_RESULT_ALREADY_RESOLVED);
        }
        return saved;
    }
}
