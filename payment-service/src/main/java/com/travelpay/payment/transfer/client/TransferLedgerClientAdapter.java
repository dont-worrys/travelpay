package com.travelpay.payment.transfer.client;

import java.util.Map;
import java.util.Optional;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse.RejectReason;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * 원장 거래 등록 응답을 송금 쪽 결과로 해석합니다. 응답 본문은 결과·예외 메시지에 싣지 않고 오류 코드만 사용합니다.
 * <ul>
 *     <li>201·200 + status POSTED → {@link LedgerTransactionResponse#posted}</li>
 *     <li>422 + LEDGER_REJECTED_* → {@link LedgerTransactionResponse#rejected} (같은 reference 재요청도 같은 422)</li>
 *     <li>타임아웃·연결 실패·Eureka 미등록(503)·5xx·해석할 수 없는 성공 응답 → {@link TransferLedgerException.Kind#RESULT_UNKNOWN}</li>
 *     <li>그 밖의 4xx(409·400·알 수 없는 422 코드 등) → {@link TransferLedgerException.Kind#REQUEST_NOT_ACCEPTED}</li>
 * </ul>
 * 거절 코드는 원장 담당자가 확인한 422 코드 세 가지입니다(2026-11-02).
 */
@Component
public class TransferLedgerClientAdapter implements TransferLedgerClient {

    private static final int UNPROCESSABLE_ENTITY = 422;
    private static final String POSTED = "POSTED";
    private static final Map<String, RejectReason> REJECT_REASONS = Map.of(
            "LEDGER_REJECTED_INSUFFICIENT_BALANCE", RejectReason.INSUFFICIENT_BALANCE,
            "LEDGER_REJECTED_WALLET_SUSPENDED", RejectReason.WALLET_SUSPENDED,
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND", RejectReason.ACCOUNT_NOT_FOUND
    );

    private final TransferLedgerFeignClient feignClient;

    public TransferLedgerClientAdapter(TransferLedgerFeignClient feignClient) {
        this.feignClient = feignClient;
    }

    @Override
    public LedgerTransactionResponse register(LedgerTransactionRequest request) {
        ApiResponse<LedgerRegisterResponse> response;
        try {
            response = feignClient.register(request);
        } catch (FeignException exception) {
            return rejectedOrThrow(exception);
        }
        // 2xx를 받았으면 원장이 기록했을 수 있으므로, 본문을 해석할 수 없어도 실패로 보지 않습니다.
        if (response == null || !response.success() || response.data() == null
                || response.data().transactionId() == null || !POSTED.equals(response.data().status())) {
            throw TransferLedgerException.resultUnknown("원장 성공 응답을 해석할 수 없습니다.", null);
        }
        return LedgerTransactionResponse.posted(response.data().transactionId());
    }

    private static LedgerTransactionResponse rejectedOrThrow(FeignException exception) {
        int status = exception.status();
        Optional<String> errorCode = FeignErrors.errorCode(exception);
        if (status == UNPROCESSABLE_ENTITY) {
            Optional<RejectReason> reason = errorCode.map(REJECT_REASONS::get);
            if (reason.isPresent()) {
                // 거절 응답에는 거래 ID가 없습니다.
                return LedgerTransactionResponse.rejected(null, reason.get());
            }
        }
        String message = "원장 거래 등록 실패 status=" + status + " code=" + errorCode.orElse("-");
        if (FeignErrors.isRejected(exception)) {
            throw TransferLedgerException.requestNotAccepted(message, exception);
        }
        throw TransferLedgerException.resultUnknown(message, exception);
    }
}
