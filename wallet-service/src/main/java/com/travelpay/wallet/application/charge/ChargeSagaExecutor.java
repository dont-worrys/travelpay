package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.application.charge.ChargeStatusTransitioner.UnknownRecord;
import com.travelpay.wallet.application.charge.dto.BankResult;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.AccountNumberCipherException;
import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.travelpay.wallet.domain.charge.ChargeStatus.BANK_PROCESSING;
import static com.travelpay.wallet.domain.charge.ChargeStatus.COMPLETED;
import static com.travelpay.wallet.domain.charge.ChargeStatus.CREDIT_PROCESSING;
import static com.travelpay.wallet.domain.charge.ChargeStatus.FAILED;
import static com.travelpay.wallet.domain.charge.ChargeStatus.MANUAL_REVIEW;
import static com.travelpay.wallet.domain.charge.ChargeStatus.REFUNDED;
import static com.travelpay.wallet.domain.charge.ChargeStatus.REFUND_PROCESSING;
import static com.travelpay.wallet.domain.charge.ChargeStatus.REQUESTED;

/**
 * 충전 SAGA 실행기. 현재 상태의 단계를 실행하고, 성공하면 다음 단계를 이어서 진행한다.
 * 은행·원장 호출은 트랜잭션 밖에서 하고 상태 변경만 ChargeSagaSteps의 짧은 트랜잭션으로 한다.
 * - 결과 모름(타임아웃·5xx·연결 실패): 상태 유지, attempt_count +1 (한도에 이르면 MANUAL_REVIEW) 후 멈춤
 * - 409·400·응답 형식 이상: 재시도로 풀리지 않으므로 바로 MANUAL_REVIEW(RESPONSE_INVALID)
 * - 전이가 0행(다른 실행기가 먼저 진행): 다음 외부 호출 없이 멈춤
 * 로그에는 chargeId·bankRequestId·eventId·예외 클래스명만 남긴다 (계좌번호·예금주 금지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeSagaExecutor {

    /** 각 처리 중 상태에서 다른 실행기가 먼저 진행했을 때 있을 수 있는 상태 */
    private static final Map<ChargeStatus, Set<ChargeStatus>> LATER_STATUSES = Map.of(
            REQUESTED, EnumSet.complementOf(EnumSet.of(REQUESTED)),
            BANK_PROCESSING, EnumSet.of(CREDIT_PROCESSING, COMPLETED, FAILED, REFUND_PROCESSING, REFUNDED,
                    MANUAL_REVIEW),
            CREDIT_PROCESSING, EnumSet.of(COMPLETED, REFUND_PROCESSING, REFUNDED, MANUAL_REVIEW),
            REFUND_PROCESSING, EnumSet.of(REFUNDED, MANUAL_REVIEW));

    /** 은행 출금 실패 코드 → 충전 실패 코드. 없는 코드는 BANK_REJECTED */
    private static final Map<String, ChargeFailureCode> WITHDRAW_FAILURES = Map.of(
            "ACCOUNT_NOT_FOUND", ChargeFailureCode.BANK_ACCOUNT_NOT_FOUND,
            "ACCOUNT_SUSPENDED", ChargeFailureCode.BANK_ACCOUNT_SUSPENDED,
            "HOLDER_MISMATCH", ChargeFailureCode.BANK_HOLDER_MISMATCH,
            "INSUFFICIENT_BALANCE", ChargeFailureCode.BANK_INSUFFICIENT_BALANCE);

    private final ChargeSagaSteps steps;
    private final BankClient bankClient;
    private final LedgerTransactionClient ledgerClient;
    private final AccountNumberCipher cipher;

    public void execute(UUID chargeId) {
        execute(chargeId, null);
    }

    /** @param eventId 상태 이력 trace_id와 로그에 남긴다 (없으면 null) */
    public void execute(UUID chargeId, String eventId) {
        Optional<ChargeRequest> loaded = steps.load(chargeId);
        if (loaded.isEmpty()) {
            log.error("충전 SAGA 대상 충전 없음 chargeId={} eventId={}", chargeId, eventId);
            return;
        }
        ChargeRequest charge = loaded.get();
        while (charge.getStatus().isInProgress()) {
            Optional<ChargeStatus> advancedTo = runStep(charge, eventId);
            if (advancedTo.isEmpty()) {
                return;
            }
            Optional<ChargeRequest> reloaded = steps.load(chargeId);
            // 방금 바꾼 상태가 아니면 다른 실행기가 이어서 진행한 것
            if (reloaded.isEmpty() || reloaded.get().getStatus() != advancedTo.get()) {
                return;
            }
            charge = reloaded.get();
        }
    }

    /** 전이했으면 바뀐 상태, 멈춰야 하면 empty */
    private Optional<ChargeStatus> runStep(ChargeRequest charge, String eventId) {
        return switch (charge.getStatus()) {
            case REQUESTED -> advance(charge, REQUESTED, BANK_PROCESSING, ChargeStepResult.none(), eventId);
            case BANK_PROCESSING -> withdraw(charge, eventId);
            case CREDIT_PROCESSING -> credit(charge, eventId);
            case REFUND_PROCESSING -> refund(charge, eventId);
            case COMPLETED, FAILED, REFUNDED, MANUAL_REVIEW -> Optional.empty();
        };
    }

    private Optional<ChargeStatus> withdraw(ChargeRequest charge, String eventId) {
        String accountNumber;
        try {
            accountNumber = cipher.decrypt(charge.getBankAccount().getAccountNumberEnc());
        } catch (AccountNumberCipherException e) {
            log.error("충전 계좌번호 복호화 실패 chargeId={} bankRequestId={} eventId={} exception={}",
                    charge.getId(), charge.getBankRequestId(), eventId, e.getClass().getName());
            return Optional.empty();
        }
        BankResult result = bankClient.withdraw(charge.getBankRequestId(), charge.getBankAccount().getBankCode(),
                accountNumber, charge.getHolderNameSnapshot(), charge.getAmount());
        return switch (result) {
            case BankResult.Succeeded succeeded -> advance(charge, BANK_PROCESSING, CREDIT_PROCESSING,
                    ChargeStepResult.withdrawn(succeeded.bankTxId()), eventId);
            case BankResult.Failed failed -> advance(charge, BANK_PROCESSING, FAILED,
                    withdrawFailure(failed.failureCode()), eventId);
            case BankResult.Unknown unknown -> recordUnknown(charge, BANK_PROCESSING, unknown.errorClass(), eventId);
            case BankResult.Invalid invalid -> toManualReview(charge, BANK_PROCESSING, invalid.cause(), eventId);
        };
    }

    private Optional<ChargeStatus> credit(ChargeRequest charge, String eventId) {
        LedgerPostResult result = ledgerClient.postCharge(charge.getId(), charge.getUserId(), charge.getWalletId(),
                charge.getAmount());
        return switch (result) {
            case LedgerPostResult.Posted posted -> advance(charge, CREDIT_PROCESSING, COMPLETED,
                    ChargeStepResult.credited(posted.transactionId()), eventId);
            // REFUND_PROCESSING 전이는 원장 422 분기에서만
            case LedgerPostResult.Rejected rejected -> advance(charge, CREDIT_PROCESSING, REFUND_PROCESSING,
                    ChargeStepResult.failed(ChargeFailureCode.LEDGER_REJECTED, rejected.errorCode()), eventId);
            case LedgerPostResult.Unknown unknown ->
                    recordUnknown(charge, CREDIT_PROCESSING, unknown.errorClass(), eventId);
            case LedgerPostResult.Invalid invalid ->
                    toManualReview(charge, CREDIT_PROCESSING, invalid.cause(), eventId);
        };
    }

    private Optional<ChargeStatus> refund(ChargeRequest charge, String eventId) {
        if (charge.getBankTxId() == null) {
            log.error("환불할 출금 거래 ID 없음 chargeId={} bankRequestId={} eventId={}",
                    charge.getId(), charge.getBankRequestId(), eventId);
            return Optional.empty();
        }
        BankResult result = bankClient.refund(charge.getRefundRequestId(), charge.getBankTxId(), charge.getAmount());
        return switch (result) {
            case BankResult.Succeeded succeeded -> advance(charge, REFUND_PROCESSING, REFUNDED,
                    ChargeStepResult.refunded(succeeded.bankTxId()), eventId);
            // failure_code는 LEDGER_REJECTED를 유지하고 환불 실패 코드는 last_error에 남긴다
            case BankResult.Failed failed -> advance(charge, REFUND_PROCESSING, MANUAL_REVIEW,
                    ChargeStepResult.failed(null, "refund failureCode=" + failed.failureCode()), eventId);
            case BankResult.Unknown unknown ->
                    recordUnknown(charge, REFUND_PROCESSING, unknown.errorClass(), eventId);
            case BankResult.Invalid invalid -> toManualReview(charge, REFUND_PROCESSING, invalid.cause(), eventId);
        };
    }

    private static ChargeStepResult withdrawFailure(String bankFailureCode) {
        ChargeFailureCode mapped = bankFailureCode == null ? null : WITHDRAW_FAILURES.get(bankFailureCode);
        if (mapped != null) {
            return ChargeStepResult.failed(mapped, null);
        }
        return ChargeStepResult.failed(ChargeFailureCode.BANK_REJECTED, "bank failureCode=" + bankFailureCode);
    }

    private Optional<ChargeStatus> advance(ChargeRequest charge, ChargeStatus from, ChargeStatus to,
                                           ChargeStepResult result, String eventId) {
        if (steps.advance(charge.getId(), from, to, result, eventId)) {
            return Optional.of(to);
        }
        checkLostTransition(charge, from, eventId);
        return Optional.empty();
    }

    private Optional<ChargeStatus> recordUnknown(ChargeRequest charge, ChargeStatus status, String errorClass,
                                                 String eventId) {
        UnknownRecord record = steps.recordUnknown(charge.getId(), status, errorClass, eventId);
        switch (record) {
            case RECORDED -> log.warn("충전 단계 결과 모름 chargeId={} bankRequestId={} eventId={} exception={}",
                    charge.getId(), charge.getBankRequestId(), eventId, errorClass);
            case EXHAUSTED -> log.error("충전 결과 모름 한도 초과로 수동 확인 전환 chargeId={} bankRequestId={} eventId={} "
                    + "exception={}", charge.getId(), charge.getBankRequestId(), eventId, errorClass);
            case NOT_RECORDED -> checkLostTransition(charge, status, eventId);
        }
        return Optional.empty();
    }

    private Optional<ChargeStatus> toManualReview(ChargeRequest charge, ChargeStatus from, String cause,
                                                  String eventId) {
        log.error("충전 외부 응답 이상으로 수동 확인 전환 chargeId={} bankRequestId={} eventId={} exception={}",
                charge.getId(), charge.getBankRequestId(), eventId, cause);
        return advance(charge, from, MANUAL_REVIEW,
                ChargeStepResult.failed(ChargeFailureCode.RESPONSE_INVALID, cause), eventId);
    }

    /** 전이가 0행이면 현재 상태를 다시 읽어, 다른 실행기가 진행한 것이 아니면 ERROR로 남긴다 */
    private void checkLostTransition(ChargeRequest charge, ChargeStatus from, String eventId) {
        Optional<ChargeStatus> current = steps.load(charge.getId()).map(ChargeRequest::getStatus);
        if (current.isPresent() && LATER_STATUSES.get(from).contains(current.get())) {
            return;
        }
        log.error("충전 상태 전이 실패, 예상 밖 상태 chargeId={} bankRequestId={} eventId={}",
                charge.getId(), charge.getBankRequestId(), eventId);
    }
}
