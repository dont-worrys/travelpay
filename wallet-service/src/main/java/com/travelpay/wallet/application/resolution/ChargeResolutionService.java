package com.travelpay.wallet.application.resolution;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.wallet.application.charge.BankWithdrawFailures;
import com.travelpay.wallet.application.charge.IdempotencyKeys;
import com.travelpay.wallet.application.reconciliation.BankTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.LedgerTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ChargeFacts;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.application.resolution.dto.ChargeResolutionResult;
import com.travelpay.wallet.application.resolution.dto.ChargeReviewResult;
import com.travelpay.wallet.application.resolution.dto.ResolveChargeCommand;
import com.travelpay.wallet.application.resolution.dto.SourceFact;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.reconciliation.ReconciliationResult;
import com.travelpay.wallet.domain.reconciliation.ResolutionStatus;
import com.travelpay.wallet.domain.resolution.ChargeResolution;
import com.travelpay.wallet.domain.resolution.DuplicateChargeResolutionException;
import com.travelpay.wallet.domain.resolution.ResolutionAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * MANUAL_REVIEW 충전 보정. 운영자 입력·대사 결과를 믿지 않고, 실행 순간 은행·원장을 다시 조회한 사실로만 판단한다.
 * 보정은 돈을 움직이지 않고 상태를 확정하거나 기존 SAGA 단계로 되돌린다.
 *
 * MANUAL_REVIEW 전환 시각은 충전 updated_at이다. MANUAL_REVIEW인 동안 충전 행을 갱신하는 경로가 없고
 * (모든 UPDATE가 처리 중 상태 조건), SAGA는 MANUAL_REVIEW에서 멈추므로 외부 요청은 모두 그 전에 나갔다.
 * 그래도 응답 지연을 감안해 전환 후 설정 시간이 지난 NotFound만 "없음"으로 인정한다.
 *
 * 외부 조회는 트랜잭션 밖에서 하고, 반영은 ChargeResolutionTransactionSteps 한 트랜잭션으로 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeResolutionService {

    private static final int REASON_LOG_MAX = 100;

    private final ChargeResolutionTransactionSteps steps;
    private final BankTransactionQueryClient bankClient;
    private final LedgerTransactionQueryClient ledgerClient;
    private final ChargeResolutionSettings settings;

    /** 지금 사실과 가능한 조치. 아무것도 바꾸지 않는다. 조회 불가 소스는 UNAVAILABLE로 보여 준다 */
    public ChargeReviewResult review(UUID chargeId) {
        Objects.requireNonNull(chargeId, "chargeId");
        ChargeRequest charge = loadManualReview(chargeId);
        Snapshot snapshot = snapshot(charge);
        ChargeResolutionJudge.Assessment assessment = snapshot.assessment();
        List<ChargeReviewResult.UnavailableAction> unavailable = new ArrayList<>();
        for (ResolutionAction action : ResolutionAction.values()) {
            if (!assessment.allows(action)) {
                unavailable.add(new ChargeReviewResult.UnavailableAction(action, assessment.reasonsAgainst(action)));
            }
        }
        return new ChargeReviewResult(charge.getId(), charge.getStatus(), charge.getAmount(), charge.getCurrency(),
                charge.getUpdatedAt(), snapshot.notFoundConfirmedAt(), snapshot.checkedAt(),
                snapshot.withdrawal(), snapshot.refund(), snapshot.ledger(),
                assessment.availableActions(), assessment.blockers(), unavailable);
    }

    /**
     * 보정 실행.
     * - 같은 키 성공 기록: 내용이 같으면 그 결과(재조회·재반영 없음), 다르면 409
     * - 충전 없음 404, MANUAL_REVIEW 아님 409, 대사 결과가 다른 충전 것 400·이미 처리됨 409
     * - 조회 불가 503, 판정 불가 409 (둘 다 키를 소비하지 않음, 지금 사실을 응답 details에)
     * - 반영 중 같은 키 UNIQUE 위반·UPDATE 0건: 롤백 후 키로 다시 조회해 같은 내용의 성공 기록이면 그 결과, 아니면 409
     */
    public ChargeResolutionResult resolve(ResolveChargeCommand command) {
        UUID idempotencyKey = IdempotencyKeys.parse(command.idempotencyKey());
        validate(command);

        Optional<ChargeResolution> existing = steps.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        ChargeRequest charge = loadManualReview(command.chargeId());
        if (command.reconciliationResultId() != null) {
            requireLinkableResult(command.reconciliationResultId(), charge.getId());
        }

        Snapshot snapshot = snapshot(charge);
        ChargeResolutionJudge.Assessment assessment = snapshot.assessment();
        if (snapshot.anyUnavailable()) {
            warnNotApplied(command, "조회 불가", assessment.blockers());
            throw new ChargeResolutionRejectedException(WalletErrorCode.CHARGE_RESOLUTION_SOURCE_UNAVAILABLE,
                    details(assessment.blockers(), snapshot));
        }
        if (!assessment.allows(command.action())) {
            List<String> reasons = assessment.reasonsAgainst(command.action());
            warnNotApplied(command, "판정 불가", reasons);
            throw new ChargeResolutionRejectedException(WalletErrorCode.CHARGE_RESOLUTION_REJECTED,
                    details(reasons, snapshot));
        }

        ChargeResolution record = ChargeResolution.record(idempotencyKey, charge.getId(), command.operatorId(),
                command.action(), command.reason(), snapshot.withdrawal().toEvidence(),
                snapshot.refund().toEvidence(), snapshot.ledger().toEvidence(), command.reconciliationResultId());
        ChargeResolution saved;
        try {
            saved = steps.apply(record, resultFailureCode(command.action(), snapshot.debit()),
                    assessment.verifiedBankTxId(), assessment.verifiedLedgerRef(),
                    assessment.verifiedRefundBankTxId());
        } catch (DuplicateChargeResolutionException | ResolutionNotAppliedException e) {
            // 같은 키의 동시 요청이 먼저 커밋됐거나, 다른 보정이 먼저 반영됨. 새 트랜잭션에서 키로 다시 읽는다
            return steps.findByIdempotencyKey(idempotencyKey)
                    .map(winner -> replay(winner, command))
                    .orElseThrow(() -> new BusinessException(WalletErrorCode.CHARGE_NOT_IN_MANUAL_REVIEW));
        }
        log.info("충전 보정 반영 resolutionId={} chargeId={} operatorId={} action={} resultStatus={} "
                        + "reconciliationResultId={}", saved.getId(), saved.getChargeId(), saved.getOperatorId(),
                saved.getAction(), saved.getResultStatus(), saved.getReconciliationResultId());
        return ChargeResolutionResult.from(saved);
    }

    /**
     * 보정 후 충전 failure_code. MANUAL_REVIEW 진입 원인(RETRY_EXHAUSTED 등)은 남기지 않는다 (상태 이력·보정 기록에만 있음).
     * 원칙: 정상 흐름에서 그 상태에 도달했을 때와 같은 값.
     * - CONFIRM_COMPLETED·RESUME_CREDIT: 비움
     * - CONFIRM_FAILED: 출금 FAILED면 SAGA와 같은 은행 실패 매핑(없는 코드는 BANK_REJECTED), 출금 없음이면 BANK_NOT_PROCESSED
     * - CONFIRM_REFUNDED·RESUME_REFUND: LEDGER_REJECTED (정상 흐름은 원장 거절로 REFUND_PROCESSING에 들어올 때 넣고
     *   환불 성공까지 유지한다. 두 조치 모두 원장 REJECTED 또는 없음을 확인해야 허용된다)
     */
    private static ChargeFailureCode resultFailureCode(ResolutionAction action,
                                                       SourceLookup<BankTransactionRecord> debit) {
        return switch (action) {
            case CONFIRM_COMPLETED, RESUME_CREDIT -> null;
            case CONFIRM_REFUNDED, RESUME_REFUND -> ChargeFailureCode.LEDGER_REJECTED;
            case CONFIRM_FAILED -> debit instanceof SourceLookup.Found<BankTransactionRecord> found
                    ? BankWithdrawFailures.toChargeFailureCode(found.record().failureCode())
                    : ChargeFailureCode.BANK_NOT_PROCESSED;
        };
    }

    private static void validate(ResolveChargeCommand command) {
        Objects.requireNonNull(command.chargeId(), "chargeId");
        Objects.requireNonNull(command.operatorId(), "operatorId");
        String reason = command.reason();
        if (command.action() == null || reason == null || reason.isBlank()
                || reason.length() > ChargeResolution.REASON_MAX) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    private static ChargeResolutionResult replay(ChargeResolution existing, ResolveChargeCommand command) {
        if (!existing.isSameRequest(command.chargeId(), command.operatorId(), command.action(), command.reason(),
                command.reconciliationResultId())) {
            throw new BusinessException(WalletErrorCode.CHARGE_RESOLUTION_IDEMPOTENCY_CONFLICT);
        }
        return ChargeResolutionResult.from(existing);
    }

    private ChargeRequest loadManualReview(UUID chargeId) {
        ChargeRequest charge = steps.findCharge(chargeId)
                .orElseThrow(() -> new BusinessException(WalletErrorCode.CHARGE_NOT_FOUND));
        if (charge.getStatus() != ChargeStatus.MANUAL_REVIEW) {
            throw new BusinessException(WalletErrorCode.CHARGE_NOT_IN_MANUAL_REVIEW);
        }
        return charge;
    }

    /** 없는 결과·다른 충전(고아 포함)의 결과는 400, 이미 처리된 결과는 409 */
    private void requireLinkableResult(UUID resultId, UUID chargeId) {
        ReconciliationResult result = steps.findReconciliationResult(resultId)
                .filter(found -> chargeId.equals(found.getChargeId()))
                .orElseThrow(() -> new BusinessException(WalletErrorCode.RECONCILIATION_RESULT_NOT_FOR_CHARGE));
        if (result.getResolutionStatus() != ResolutionStatus.PENDING) {
            throw new BusinessException(WalletErrorCode.RECONCILIATION_RESULT_ALREADY_RESOLVED);
        }
    }

    /** 은행 출금·환불·원장 단건 재조회와 판정. 트랜잭션 밖에서 호출한다 */
    private Snapshot snapshot(ChargeRequest charge) {
        SourceLookup<BankTransactionRecord> debit =
                query(() -> bankClient.findTransaction(charge.getBankRequestId()));
        SourceLookup<BankTransactionRecord> refund =
                query(() -> bankClient.findTransaction(charge.getRefundRequestId()));
        SourceLookup<LedgerTransactionRecord> ledger =
                query(() -> ledgerClient.findChargeTransaction(charge.getId()));
        Instant checkedAt = Instant.now();
        Instant notFoundConfirmedAt = charge.getUpdatedAt().plus(settings.notFoundConfirmAfter());
        boolean notFoundConfirmed = !checkedAt.isBefore(notFoundConfirmedAt);
        ChargeFacts facts = new ChargeFacts(debit, refund, ledger);
        return new Snapshot(checkedAt, notFoundConfirmedAt, debit, SourceFact.ofBank(debit), SourceFact.ofBank(refund),
                SourceFact.ofLedger(ledger, charge.getWalletId()),
                ChargeResolutionJudge.assess(charge, facts, notFoundConfirmed));
    }

    /** 포트 계약상 실패는 Unavailable로 오지만, 예상 밖 예외도 조회 불가로 본다 */
    private static <T> SourceLookup<T> query(Supplier<SourceLookup<T>> call) {
        try {
            SourceLookup<T> result = call.get();
            return result != null ? result : SourceLookup.unavailable("null response");
        } catch (RuntimeException e) {
            log.warn("충전 보정 단건 조회 실패 exception={}", e.getClass().getName());
            return SourceLookup.unavailable(e.getClass().getName());
        }
    }

    private static List<ErrorResponse.FieldError> details(List<String> reasons, Snapshot snapshot) {
        List<ErrorResponse.FieldError> details = new ArrayList<>();
        for (String reason : reasons) {
            details.add(new ErrorResponse.FieldError("reason", reason));
        }
        details.add(new ErrorResponse.FieldError("withdrawal", snapshot.withdrawal().summary()));
        details.add(new ErrorResponse.FieldError("refund", snapshot.refund().summary()));
        details.add(new ErrorResponse.FieldError("ledger", snapshot.ledger().summary()));
        details.add(new ErrorResponse.FieldError("checkedAt", snapshot.checkedAt().toString()));
        details.add(new ErrorResponse.FieldError("notFoundConfirmedAt", snapshot.notFoundConfirmedAt().toString()));
        return details;
    }

    /** 감사용. 키를 소비하지 않으므로 DB에 남기지 않고 로그만 남긴다 */
    private static void warnNotApplied(ResolveChargeCommand command, String kind, List<String> reasons) {
        String reason = command.reason();
        String summary = reason.length() <= REASON_LOG_MAX ? reason : reason.substring(0, REASON_LOG_MAX) + "…";
        log.warn("충전 보정 거절({}) chargeId={} operatorId={} action={} reasonSummary={} rejections={}",
                kind, command.chargeId(), command.operatorId(), command.action(), summary, reasons);
    }

    /** debit: 은행 출금 조회 원본 (CONFIRM_FAILED의 은행 실패 코드를 읽는다) */
    private record Snapshot(Instant checkedAt, Instant notFoundConfirmedAt, SourceLookup<BankTransactionRecord> debit,
                            SourceFact withdrawal, SourceFact refund, SourceFact ledger,
                            ChargeResolutionJudge.Assessment assessment) {

        boolean anyUnavailable() {
            return withdrawal.isUnavailable() || refund.isUnavailable() || ledger.isUnavailable();
        }
    }
}
