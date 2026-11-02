package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.BankClient;
import com.travelpay.wallet.application.charge.dto.BankResult;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 은행 응답 해석. 은행 응답 내용(계좌·예금주)은 결과·로그에 싣지 않는다.
 * - 2xx + status SUCCEEDED(bankTxId 있음): Succeeded
 * - 2xx + status FAILED: Failed (failureCode 그대로, 매핑은 SAGA가 함)
 * - 2xx 내용 이상(status·bankTxId 누락, 다른 requestId·유형): Invalid
 * - 4xx(409·400 등): Invalid
 * - 4xx가 아닌 모든 실패(5xx·타임아웃·연결 실패·2xx 본문 해석 실패): Unknown (같은 requestId로 다시 보내면 됨).
 *   요청 전송 전 실패(EncodeException)·3xx도 Unknown이다. 어느 쪽이든 보상하지 않으므로 안전한 쪽으로 둔다
 */
@Component
@RequiredArgsConstructor
public class BankClientAdapter implements BankClient {

    private static final String DEBIT = "DEBIT";
    private static final String REFUND = "REFUND";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private final BankFeignClient feignClient;

    @Override
    public BankResult withdraw(String requestId, String bankCode, String accountNumber, String holderName,
                               long amount) {
        return call(requestId, DEBIT, () -> feignClient.withdraw(
                new BankWithdrawalRequest(requestId, bankCode, accountNumber, holderName, amount)));
    }

    @Override
    public BankResult refund(String requestId, UUID originalTxId, long amount) {
        return call(requestId, REFUND, () -> feignClient.refund(
                new BankRefundRequest(requestId, originalTxId, amount)));
    }

    private BankResult call(String requestId, String expectedType,
                            Supplier<ApiResponse<BankTransactionResponse>> request) {
        ApiResponse<BankTransactionResponse> response;
        try {
            response = request.get();
        } catch (FeignException e) {
            if (!FeignErrors.isRejected(e)) {
                return new BankResult.Unknown(e.getClass().getName());
            }
            return new BankResult.Invalid(e.getClass().getName());
        }
        return interpret(response, requestId, expectedType);
    }

    private static BankResult interpret(ApiResponse<BankTransactionResponse> response, String requestId,
                                        String expectedType) {
        if (response == null || !response.success() || response.data() == null) {
            return invalid("body");
        }
        BankTransactionResponse data = response.data();
        if (!Objects.equals(requestId, data.requestId()) || !expectedType.equals(data.type())) {
            return invalid("requestId/type");
        }
        if (SUCCEEDED.equals(data.status())) {
            return data.bankTxId() != null ? new BankResult.Succeeded(data.bankTxId()) : invalid("bankTxId");
        }
        if (FAILED.equals(data.status())) {
            return new BankResult.Failed(data.failureCode());
        }
        return invalid("status");
    }

    private static BankResult invalid(String field) {
        return new BankResult.Invalid("bank response invalid: " + field);
    }
}
