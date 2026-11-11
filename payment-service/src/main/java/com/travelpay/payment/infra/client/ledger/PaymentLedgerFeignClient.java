package com.travelpay.payment.infra.client.ledger;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerResult;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerSettlementCommand;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerTransactionCommand;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.Entry;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.RegisterRequest;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.TransactionResponse;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.TransactionResultResponse;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentLedgerFeignClient implements PaymentLedgerClient {

    private static final String REJECTED_CODE_PREFIX = "LEDGER_REJECTED_";
    private static final String TRANSACTION_NOT_FOUND_CODE = "LEDGER_TRANSACTION_NOT_FOUND";

    /** 원장 거절 코드별 결제 거절 사유. 목록에 없는 거절 코드는 기본 사유로 남긴다 */
    private static final DeclineReasons PAYMENT_DECLINE_REASONS = new DeclineReasons(Map.of(
            "LEDGER_REJECTED_INSUFFICIENT_BALANCE", "잔액이 부족합니다.",
            "LEDGER_REJECTED_WALLET_SUSPENDED", "사용할 수 없는 지갑입니다.",
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND", "결제할 수 있는 계정이 없습니다."),
            "결제가 거절되었습니다.");

    /** 원장 거절 코드별 환불 거절 사유. 잔액 부족(가맹점 잔액)은 따로 안내하지 않고 기본 사유로 남긴다 */
    private static final DeclineReasons REFUND_DECLINE_REASONS = new DeclineReasons(Map.of(
            "LEDGER_REJECTED_WALLET_SUSPENDED", "환불할 수 없는 계정입니다.",
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND", "환불할 수 있는 계정이 없습니다."),
            "환불이 거절되었습니다.");

    private static final String SETTLEMENT_REFERENCE_TYPE = "SETTLEMENT";
    private static final String SETTLEMENT_OUT = "SETTLEMENT_OUT";
    private static final String SETTLEMENT_IN = "SETTLEMENT_IN";

    /** 원장 거절 코드별 정산 거절 사유 */
    private static final DeclineReasons SETTLEMENT_DECLINE_REASONS = new DeclineReasons(Map.of(
            "LEDGER_REJECTED_INSUFFICIENT_BALANCE", "가맹점 잔액이 부족합니다.",
            "LEDGER_REJECTED_WALLET_SUSPENDED", "정산 받을 지갑을 사용할 수 없습니다.",
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND", "정산할 수 있는 계정이 없습니다."),
            "정산이 거절되었습니다.");

    private final LedgerFeignApi ledgerFeignApi;
    // 거래 등록(결제·환불·정산)에만 사용. 조회(lookup)는 운영자 처리용이라 서킷을 거치지 않음
    private final CircuitBreaker circuitBreaker;

    @Override
    public PaymentLedgerResult createTransaction(PaymentLedgerTransactionCommand command) {
        String currency = command.currency().name();
        return register(new RegisterRequest("PAYMENT", command.referenceId(), command.userId(), null, List.of(
                Entry.user(command.userWalletId(), currency, "DEBIT", command.amount(), "PAYMENT_OUT"),
                Entry.user(command.merchantWalletId(), currency, "CREDIT", command.amount(), "PAYMENT_IN"))),
                PAYMENT_DECLINE_REASONS);
    }

    @Override
    public PaymentLedgerResult refundTransaction(PaymentLedgerRefundCommand command) {
        String currency = command.currency().name();
        return register(new RegisterRequest("PAYMENT_REFUND", command.referenceId(), command.userId(),
                command.originalTransactionId(), List.of(
                Entry.user(command.merchantWalletId(), currency, "DEBIT", command.amount(), "PAYMENT_REFUND_OUT"),
                Entry.user(command.userWalletId(), currency, "CREDIT", command.amount(), "PAYMENT_REFUND_IN"))),
                REFUND_DECLINE_REASONS);
    }

    // 정산: 가맹점 원장 지갑 차감, 가맹점 주인 지갑 적립
    @Override
    public PaymentLedgerResult settlementTransaction(PaymentLedgerSettlementCommand command) {
        String currency = command.currency().name();
        return register(new RegisterRequest(SETTLEMENT_REFERENCE_TYPE, command.referenceId(), command.ownerUserId(),
                null, List.of(
                Entry.user(command.merchantWalletId(), currency, "DEBIT", command.amount(), SETTLEMENT_OUT),
                Entry.user(command.ownerWalletId(), currency, "CREDIT", command.amount(), SETTLEMENT_IN))),
                SETTLEMENT_DECLINE_REASONS);
    }

    @Override
    public PaymentLedgerLookup findPaymentTransaction(UUID paymentId) {
        return lookup("PAYMENT", paymentId, PAYMENT_DECLINE_REASONS);
    }

    @Override
    public PaymentLedgerLookup findRefundTransaction(UUID refundId) {
        return lookup("PAYMENT_REFUND", refundId, REFUND_DECLINE_REASONS);
    }

    @Override
    public PaymentLedgerLookup findSettlementTransaction(UUID settlementId) {
        return lookup(SETTLEMENT_REFERENCE_TYPE, settlementId, SETTLEMENT_DECLINE_REASONS);
    }

    private PaymentLedgerLookup lookup(String referenceType, UUID referenceId, DeclineReasons declineReasons) {
        ApiResponse<TransactionResultResponse> response;
        try {
            response = ledgerFeignApi.getTransaction(referenceType, referenceId);
        } catch (FeignException exception) {
            // 원장이 "기록 없음"이라고 답한 경우만 NOT_FOUND. 코드 없는 404(경로 오류 등)는 기록 없음으로 단정하지 않는다
            if (exception.status() == 404 && FeignErrors.hasErrorCode(exception, TRANSACTION_NOT_FOUND_CODE)) {
                return PaymentLedgerLookup.notFound();
            }
            log.warn("원장 거래 조회 실패 referenceType={} referenceId={} status={}",
                    referenceType, referenceId, exception.status());
            throw new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE);
        }

        TransactionResultResponse data = response == null || !response.success() ? null : response.data();
        if (data == null || data.transactionId() == null || data.status() == null) {
            log.error("원장 거래 조회 응답 형식 이상 referenceType={} referenceId={}", referenceType, referenceId);
            throw new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE);
        }
        return switch (data.status()) {
            case "POSTED" -> PaymentLedgerLookup.posted(data.transactionId(),
                    postedEntries(data, referenceType, referenceId));
            // 조회 응답의 거절 사유는 접두어 없는 이름(INSUFFICIENT_BALANCE)이라, 등록 거절 코드와 같은 표를 쓰려고 접두어를 붙인다
            case "REJECTED" -> PaymentLedgerLookup.rejected(data.transactionId(),
                    declineReasons.of(REJECTED_CODE_PREFIX + data.rejectReason()));
            default -> {
                log.error("알 수 없는 원장 거래 상태 referenceType={} referenceId={} status={}",
                        referenceType, referenceId, data.status());
                throw new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE);
            }
        };
    }

    // POSTED 거래는 항목이 있어야 우리 기록과 비교할 수 있다. 없으면 응답 형식 이상으로 보고 결론을 내지 않는다
    private static List<PaymentLedgerLookup.Entry> postedEntries(TransactionResultResponse data, String referenceType,
                                                                 UUID referenceId) {
        if (data.entries() == null || data.entries().isEmpty()
                || data.entries().stream().anyMatch(entry -> entry == null || entry.amount() == null)) {
            log.error("원장 POSTED 거래에 항목이 없거나 금액이 없음 referenceType={} referenceId={}",
                    referenceType, referenceId);
            throw new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE);
        }
        return data.entries().stream()
                .map(entry -> new PaymentLedgerLookup.Entry(entry.walletId(), entry.currency(), entry.direction(),
                        entry.amount(), entry.entryType()))
                .toList();
    }

    private PaymentLedgerResult register(RegisterRequest request, DeclineReasons declineReasons) {
        // 원장 장애가 이어져 서킷이 열려 있으면 원장에 보내지 않음
        if (!circuitBreaker.tryAcquirePermission()) {
            log.debug("원장 서킷 열림, 원장에 보내지 않음 referenceType={} referenceId={}",
                    request.referenceType(), request.referenceId());
            return PaymentLedgerResult.notSent();
        }
        long start = circuitBreaker.getCurrentTimestamp();
        ApiResponse<TransactionResponse> response;
        try {
            response = ledgerFeignApi.register(request);
        } catch (FeignException exception) {
            // 4xx 거절이 아니면 원장이 처리했을 수 있다 (연결 실패·시간 초과·5xx·2xx 본문 해석 실패). 같은 referenceId로 다시 확인한다
            if (!FeignErrors.isRejected(exception)) {
                circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), exception);
                log.warn("원장 거래 결과 모름 referenceType={} referenceId={} status={}",
                        request.referenceType(), request.referenceId(), exception.status());
                return PaymentLedgerResult.unknown();
            }
            // 4xx 거절은 원장이 정상으로 판단해 답한 것이라 서킷에서는 성공으로 판단
            circuitBreaker.onSuccess(elapsedSince(start), circuitBreaker.getTimestampUnit());
            // 원장 코드는 응답에 그대로 내보내지 않고 결제 서비스의 거절 사유로 바꾼다 (원장 코드는 로그에만 남김)
            Optional<String> rejectedCode = rejectedCode(exception);
            if (rejectedCode.isPresent()) {
                log.info("원장 거래 거절 referenceType={} referenceId={} code={}",
                        request.referenceType(), request.referenceId(), rejectedCode.get());
                return PaymentLedgerResult.declined(declineReasons.of(rejectedCode.get()));
            }
            log.error("원장이 거래 요청을 받아들이지 않음 referenceType={} referenceId={} status={} code={}",
                    request.referenceType(), request.referenceId(), exception.status(),
                    FeignErrors.errorCode(exception).orElse("-"));
            return PaymentLedgerResult.notAccepted();
        } catch (RuntimeException exception) {
            // Feign 밖의 예외도 서킷에 결과를 남겨야 반열림 시험 호출 자리가 반납
            circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), exception);
            throw exception;
        }
        // 원장이 응답에 2초 이상 걸렸으면 서킷이 느린 호출로 센다
        circuitBreaker.onSuccess(elapsedSince(start), circuitBreaker.getTimestampUnit());

        // 2xx를 받았으니 원장이 처리했을 수 있다. 내용이 이상해도 실패로 단정하지 않고 결과 모름으로 둔다
        if (response == null || !response.success() || response.data() == null
                || response.data().transactionId() == null) {
            log.error("원장 거래 응답 형식 이상, 결과 모름으로 처리 referenceType={} referenceId={}",
                    request.referenceType(), request.referenceId());
            return PaymentLedgerResult.unknown();
        }
        return PaymentLedgerResult.approved(response.data().transactionId());
    }

    private long elapsedSince(long start) {
        return circuitBreaker.getCurrentTimestamp() - start;
    }

    /** 422이고 오류 코드가 LEDGER_REJECTED_로 시작할 때만 확정 거절로 본다 */
    private static Optional<String> rejectedCode(FeignException exception) {
        if (exception.status() != 422) {
            return Optional.empty();
        }
        return FeignErrors.errorCode(exception).filter(code -> code.startsWith(REJECTED_CODE_PREFIX));
    }

    /** 원장 거절 코드를 결제 서비스의 거절 사유로 바꾸는 표. 표에 없는 코드는 기본 사유 */
    private record DeclineReasons(Map<String, String> byLedgerCode, String defaultReason) {

        String of(String ledgerCode) {
            return byLedgerCode.getOrDefault(ledgerCode, defaultReason);
        }
    }
}
