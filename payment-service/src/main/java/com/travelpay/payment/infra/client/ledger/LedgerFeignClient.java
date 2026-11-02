package com.travelpay.payment.infra.client.ledger;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.payment.client.ledger.LedgerClient;
import com.travelpay.payment.application.payment.client.ledger.LedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.LedgerResult;
import com.travelpay.payment.application.payment.client.ledger.LedgerTransactionCommand;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.Entry;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.RegisterRequest;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.TransactionResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerFeignClient implements LedgerClient {

    private static final String REJECTED_CODE_PREFIX = "LEDGER_REJECTED_";

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

    private final LedgerFeignApi ledgerFeignApi;

    @Override
    public LedgerResult createTransaction(LedgerTransactionCommand command) {
        String currency = command.currency().name();
        return register(new RegisterRequest("PAYMENT", command.referenceId(), command.userId(), null, List.of(
                Entry.user(command.userWalletId(), currency, "DEBIT", command.amount(), "PAYMENT_OUT"),
                Entry.user(command.merchantWalletId(), currency, "CREDIT", command.amount(), "PAYMENT_IN"))),
                PAYMENT_DECLINE_REASONS);
    }

    @Override
    public LedgerResult refundTransaction(LedgerRefundCommand command) {
        String currency = command.currency().name();
        return register(new RegisterRequest("PAYMENT_REFUND", command.referenceId(), command.userId(),
                command.originalTransactionId(), List.of(
                Entry.user(command.merchantWalletId(), currency, "DEBIT", command.amount(), "PAYMENT_REFUND_OUT"),
                Entry.user(command.userWalletId(), currency, "CREDIT", command.amount(), "PAYMENT_REFUND_IN"))),
                REFUND_DECLINE_REASONS);
    }

    private LedgerResult register(RegisterRequest request, DeclineReasons declineReasons) {
        ApiResponse<TransactionResponse> response;
        try {
            response = ledgerFeignApi.register(request);
        } catch (FeignException exception) {
            // 4xx 거절이 아니면 원장이 처리했을 수 있다 (연결 실패·시간 초과·5xx·2xx 본문 해석 실패). 같은 referenceId로 다시 확인한다
            if (!FeignErrors.isRejected(exception)) {
                log.warn("원장 거래 결과 모름 referenceType={} referenceId={} status={}",
                        request.referenceType(), request.referenceId(), exception.status());
                return LedgerResult.unknown();
            }
            // 원장 코드는 응답에 그대로 내보내지 않고 결제 서비스의 거절 사유로 바꾼다 (원장 코드는 로그에만 남김)
            Optional<String> rejectedCode = rejectedCode(exception);
            if (rejectedCode.isPresent()) {
                log.info("원장 거래 거절 referenceType={} referenceId={} code={}",
                        request.referenceType(), request.referenceId(), rejectedCode.get());
                return LedgerResult.declined(declineReasons.of(rejectedCode.get()));
            }
            log.error("원장 거래 응답 처리 불가 referenceType={} referenceId={} status={}",
                    request.referenceType(), request.referenceId(), exception.status());
            throw FeignErrors.toCommonException(exception);
        }

        // 2xx를 받았으니 원장이 처리했을 수 있다. 내용이 이상해도 실패로 단정하지 않고 결과 모름으로 둔다
        if (response == null || !response.success() || response.data() == null
                || response.data().transactionId() == null) {
            log.error("원장 거래 응답 형식 이상, 결과 모름으로 처리 referenceType={} referenceId={}",
                    request.referenceType(), request.referenceId());
            return LedgerResult.unknown();
        }
        return LedgerResult.approved(response.data().transactionId());
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
