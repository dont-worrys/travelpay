package com.travelpay.payment.transfer.client;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse.RejectReason;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 송금을 원장 거래 등록 API({@link LedgerFeignApi#register}, 결제와 같은 클라이언트)로 보내고 응답을 송금 쪽 결과로 해석합니다.
 * 송금은 원거래가 없으므로 originalTransactionId는 null로 보냅니다. 원장 요청 해시는 originalTransactionId를 포함하지만
 * 전환 전에도 이 필드를 보내지 않아(null) 해시가 같으므로, 진행 중인 송금의 재전송이 409가 되지 않습니다.
 * 자동 재시도는 {@code LedgerFeignConfig}에서 꺼져 있고, 타임아웃은 paymentLedgerApi 설정(연결 0.5초·읽기 6초)을 따릅니다.
 * 응답 해석: 응답 본문은 결과·예외 메시지에 싣지 않고 오류 코드만 사용합니다.
 * <ul>
 *     <li>201·200 + status POSTED → {@link LedgerTransactionResponse#posted}</li>
 *     <li>422 + LEDGER_REJECTED_* → {@link LedgerTransactionResponse#rejected} (같은 reference 재요청도 같은 422)</li>
 *     <li>타임아웃·연결 실패·Eureka 미등록(503)·5xx·해석할 수 없는 성공 응답 → {@link TransferLedgerException.Kind#RESULT_UNKNOWN}</li>
 *     <li>그 밖의 4xx(409·400·알 수 없는 422 코드 등) → {@link TransferLedgerException.Kind#REQUEST_NOT_ACCEPTED}</li>
 * </ul>
 * 거절 코드는 원장 담당자가 확인한 422 코드 세 가지입니다(2026-11-02).
 *
 * <p>서킷 브레이커(이름 transferLedger): 원장이 느리거나 응답하지 않을 때 송금 요청이 읽기 타임아웃(6초)까지 스레드를 잡지 않도록,
 * 장애가 이어지면 원장에 보내지 않고 바로 {@link TransferLedgerException.Kind#NOT_SENT}를 던집니다.
 * 송금은 PROCESSING으로 남고 시도 횟수는 늘지 않으며, 복구 스케줄러가 대기 시간 뒤 다시 보냅니다.
 * 결제 서킷(paymentLedger)과 기준은 같지만 인스턴스는 따로 둡니다. 송금 쪽 실패가 결제 서킷을 열지 않게 하려는 것이고,
 * 결제 클라이언트가 CircuitBreaker를 타입으로 주입받으므로 bean으로 등록하지 않고 이 클래스 안에서 만듭니다.
 * 상태는 Prometheus 지표 resilience4j_circuitbreaker_state{name="transferLedger"}로 나옵니다.
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

    private static final Logger log = LoggerFactory.getLogger(TransferLedgerClientAdapter.class);

    static final String CIRCUIT_BREAKER_NAME = "transferLedger";

    private final LedgerFeignApi ledgerFeignApi;
    private final CircuitBreaker circuitBreaker;

    public TransferLedgerClientAdapter(LedgerFeignApi ledgerFeignApi, MeterRegistry meterRegistry) {
        this.ledgerFeignApi = ledgerFeignApi;
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(circuitBreakerConfig());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("송금 원장 서킷 상태 변경 {}", event.getStateTransition()));
    }

    /**
     * 결제 서킷(PaymentLedgerCircuitBreakerConfig)과 같은 기준입니다. 최근 10초 동안 20건 이상 호출됐고, 그중 절반 이상이
     * 실패(타임아웃·연결 실패·5xx)이거나 2초 이상 걸리면 엽니다. 열린 뒤 10초 동안은 보내지 않고, 그 뒤 5건만 시험으로 보내
     * 결과를 보고 닫거나 다시 엽니다. 4xx(확정 거절·요청 미수락)는 원장이 정상으로 답한 것이라 성공으로 셉니다.
     */
    static CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(20)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public LedgerTransactionResponse register(LedgerTransactionRequest request) {
        if (!circuitBreaker.tryAcquirePermission()) {
            throw TransferLedgerException.notSent("원장 서킷이 열려 있어 거래 등록을 보내지 않았습니다.");
        }
        long start = circuitBreaker.getCurrentTimestamp();
        ApiResponse<LedgerFeignApi.TransactionResponse> response;
        try {
            response = ledgerFeignApi.register(toRegisterRequest(request));
        } catch (FeignException exception) {
            if (FeignErrors.isRejected(exception)) {
                circuitBreaker.onSuccess(elapsedSince(start), circuitBreaker.getTimestampUnit());
            } else {
                circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), exception);
            }
            return rejectedOrThrow(exception);
        } catch (RuntimeException exception) {
            // Feign 밖의 예외도 서킷에 결과를 남겨야 반열림 시험 호출 자리가 반납됩니다.
            circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), exception);
            throw exception;
        }
        // 응답이 2초 이상 걸렸으면 서킷이 느린 호출로 셉니다.
        circuitBreaker.onSuccess(elapsedSince(start), circuitBreaker.getTimestampUnit());
        // 2xx를 받았으면 원장이 기록했을 수 있으므로, 본문을 해석할 수 없어도 실패로 보지 않습니다.
        if (response == null || !response.success() || response.data() == null
                || response.data().transactionId() == null || !POSTED.equals(response.data().status())) {
            throw TransferLedgerException.resultUnknown("원장 성공 응답을 해석할 수 없습니다.", null);
        }
        return LedgerTransactionResponse.posted(response.data().transactionId());
    }

    private long elapsedSince(long start) {
        return circuitBreaker.getCurrentTimestamp() - start;
    }

    private static LedgerFeignApi.RegisterRequest toRegisterRequest(LedgerTransactionRequest request) {
        return new LedgerFeignApi.RegisterRequest(
                request.referenceType(),
                request.referenceId(),
                request.userId(),
                null,
                request.entries().stream()
                        .map(entry -> new LedgerFeignApi.Entry(entry.ownerType(), entry.walletId(), entry.currency(),
                                entry.direction(), entry.amount(), entry.entryType()))
                        .toList()
        );
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
