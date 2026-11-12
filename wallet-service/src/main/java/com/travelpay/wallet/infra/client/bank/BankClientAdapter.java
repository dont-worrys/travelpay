package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.BankClient;
import com.travelpay.wallet.application.charge.dto.BankResult;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>
 * 은행 코드별 서킷(BankCircuitBreakerConfig)을 거친다. 서킷이 허가하지 않으면 보내지 않고 NotSent.
 * 보낸 호출은 해석한 결과로 서킷에 기록한다 (Unknown만 실패, 나머지는 은행이 답한 것이라 성공).
 * Feign 예외가 아닌 예외(RequestInterceptor·Target·HTTP 클라이언트의 RuntimeException, UndeclaredThrowableException, Error)는
 * 은행 응답이 아니므로 기록하지 않고 허가만 반납한 뒤 다시 던진다 (반열림이면 시험 호출 자리가 돌아온다).
 * 인코딩·디코딩 중 RuntimeException은 Feign이 EncodeException·DecodeException으로 감싸므로 Unknown이다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankClientAdapter implements BankClient {

    private static final String DEBIT = "DEBIT";
    private static final String REFUND = "REFUND";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private final BankFeignClient feignClient;
    private final BankFaultInjection faultInjection;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public BankResult withdraw(String requestId, String bankCode, String accountNumber, String holderName,
                               long amount, UUID chargeId, int attemptCount) {
        String fault = faultInjection.withdrawHeader(chargeId, attemptCount);
        return call(requestId, bankCode, DEBIT, () -> feignClient.withdraw(fault,
                new BankWithdrawalRequest(requestId, bankCode, accountNumber, holderName, amount)));
    }

    @Override
    public BankResult refund(String requestId, String bankCode, UUID originalTxId, long amount) {
        return call(requestId, bankCode, REFUND, () -> feignClient.refund(
                new BankRefundRequest(requestId, originalTxId, amount)));
    }

    private BankResult call(String requestId, String bankCode, String expectedType,
                            Supplier<ApiResponse<BankTransactionResponse>> request) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(bankCode);
        // 허가를 얻지 못하면(OPEN 등) 반납하지 않는다. 아래 try는 허가를 얻은 호출만 들어간다
        if (!circuitBreaker.tryAcquirePermission()) {
            log.debug("은행 서킷 열림, 은행에 보내지 않음 requestId={} bankCode={} type={}", requestId, bankCode,
                    expectedType);
            return new BankResult.NotSent(bankCode);
        }
        long start = circuitBreaker.getCurrentTimestamp();
        boolean recorded = false;
        try {
            BankResult result = send(requestId, expectedType, request);
            // onResult를 부르는 순간부터 기록한 것으로 보고 반납하지 않는다 (이중 반납으로 반열림 자리가 늘지 않게)
            recorded = true;
            circuitBreaker.onResult(circuitBreaker.getCurrentTimestamp() - start, circuitBreaker.getTimestampUnit(),
                    result);
            return result;
        } finally {
            // 허가를 얻고 결과를 기록하지 못한 채 예외로 끝난 경우만 반납
            if (!recorded) {
                circuitBreaker.releasePermission();
            }
        }
    }

    private BankResult send(String requestId, String expectedType,
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
