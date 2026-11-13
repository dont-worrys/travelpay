package com.travelpay.exchange.infrastructure.external.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult.InvalidKind;
import com.travelpay.exchange.domain.ledger.LedgerPosting;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import feign.RetryableException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 원장 거래 등록·조회 응답을 도메인 결과로 바꿉니다. 예외를 던지지 않습니다. 원장 응답 본문은 결과·로그에 싣지 않고 error.code만 봅니다.
 *
 * [거래 등록 (post)]
 * - 201·200 + 거래 ID + POSTED: Posted
 * - 422 + LEDGER_REJECTED_*: Rejected (같은 reference로 다시 보내도 같은 422)
 * - 재시도 신호(Retry-After)가 있는 응답, 4xx로 거절되지 않은 실패(타임아웃·연결 실패·5xx·2xx인데 본문을 해석하지 못함): Unknown
 *   원장이 이미 기록했을 수 있으므로 실패로 끝내지 않고 같은 요청을 다시 보내 결과를 확인한다.
 * - 400: Invalid(BAD_REQUEST), 409: Invalid(CONFLICT)
 * - 그 밖의 4xx·따라가지 않은 3xx·본문은 읽었지만 내용이 계약과 다른 성공 응답: Invalid(MALFORMED_RESPONSE)
 * - 원장 서킷이 열려 있으면 원장에 보내지 않고 Unknown(CIRCUIT_OPEN)입니다(#282). 주문은 PROCESSING으로 남고 복구 스케줄러가 확정합니다.
 *   신청·복구 스케줄러·예약 체결이 모두 이 메서드를 거치므로 서킷 하나로 세 경로가 원장을 기다리지 않습니다
 *   (서킷 기준은 LedgerCircuitBreakerConfiguration).
 *
 * [단건 조회 (findByOrderId)] 서킷으로 감싸지 않습니다. 관리자가 가끔 하는 일이고, 원장의 실제 상태가 보여야 하기 때문입니다.
 * - 200 + POSTED + 유효한 항목: Posted
 * - 200 + REJECTED: Rejected
 * - 404 + LEDGER_TRANSACTION_NOT_FOUND: NotFound (기록 없음)
 * - 코드 없는 404·그 밖의 4xx·5xx·연결 실패·POSTED인데 항목 없음·알 수 없는 상태·reference 불일치: Unavailable (결론 내리지 않음)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerClientAdapter implements LedgerClient {

    private static final String POSTED = "POSTED";
    private static final String REJECTED = "REJECTED";
    private static final String REJECTED_PREFIX = "LEDGER_REJECTED_";
    private static final String TRANSACTION_NOT_FOUND_CODE = "LEDGER_TRANSACTION_NOT_FOUND";

    /** 서킷이 열려 원장에 보내지 않았을 때 Unknown의 원인입니다. */
    static final String CIRCUIT_OPEN = "원장 서킷 열림(보내지 않음)";

    private final LedgerFeignClient feignClient;
    private final CircuitBreaker circuitBreaker;

    /**
     * 원장에 거래 등록을 요청하고 결과를 Posted·Rejected·Unknown·Invalid 중 하나로 돌려줍니다. 실패도 예외 대신 결과로 줍니다.
     * 서킷이 걸린 시간과 결과를 기록하고(결과 모름은 실패, 2초 이상은 느린 호출), 열려 있으면 원장을 부르지 않고 바로 Unknown입니다.
     */
    @Override
    public LedgerPostResult post(LedgerPosting posting) {
        Objects.requireNonNull(posting, "posting");
        try {
            return circuitBreaker.executeSupplier(() -> register(posting));
        } catch (CallNotPermittedException e) {
            // 상태가 바뀔 때만 WARN을 남긴다(LedgerCircuitBreakerConfiguration). 열린 동안 막힌 호출마다 남기면 로그가 넘친다.
            // 막힌 수는 지표 resilience4j_circuitbreaker_not_permitted_calls_total로 본다
            log.debug("원장 서킷이 열려 보내지 않았습니다: orderId={}", posting.orderId());
            return new LedgerPostResult.Unknown(CIRCUIT_OPEN);
        }
    }

    private LedgerPostResult register(LedgerPosting posting) {
        ApiResponse<LedgerTransactionResponse> response;
        try {
            response = feignClient.register(RegisterLedgerTransactionRequest.from(posting));
        } catch (FeignException e) {
            return classify(posting, e);
        }
        LedgerTransactionResponse data = response == null ? null : response.data();
        // 본문은 읽었는데 내용이 계약과 다르면 다시 보내도 같은 응답이라 사람이 확인해야 한다
        if (data == null || data.transactionId() == null || !POSTED.equals(data.status())) {
            return invalid(posting, InvalidKind.MALFORMED_RESPONSE, "성공 응답 형식 이상");
        }
        return new LedgerPostResult.Posted(data.transactionId());
    }

    /**
     * 주문 ID로 원장 거래를 단건 조회합니다.
     * 404 + LEDGER_TRANSACTION_NOT_FOUND일 때만 NotFound(기록 없음)이고,
     * 그 밖의 오류나 비정상 응답은 주문을 잘못 취소하지 않도록 모두 Unavailable로 돌려줍니다.
     */
    @Override
    public LedgerLookupResult findByOrderId(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        ApiResponse<LedgerTransactionResponse> response;
        try {
            response = feignClient.findByReference(RegisterLedgerTransactionRequest.REFERENCE_TYPE, orderId);
        } catch (FeignException e) {
            if (e.status() == 404 && FeignErrors.hasErrorCode(e, TRANSACTION_NOT_FOUND_CODE)) {
                return new LedgerLookupResult.NotFound();
            }
            String cause = cause(e);
            log.warn("원장 거래 단건 조회에 실패했습니다: orderId={}, cause={}", orderId, cause);
            return new LedgerLookupResult.Unavailable(cause);
        }

        LedgerTransactionResponse data = response == null || !response.success() ? null : response.data();
        if (data == null || data.transactionId() == null || data.status() == null) {
            return unavailable(orderId, "조회 응답 형식 이상");
        }
        if ((data.referenceType() != null
                && !RegisterLedgerTransactionRequest.REFERENCE_TYPE.equals(data.referenceType()))
                || (data.referenceId() != null && !orderId.equals(data.referenceId()))) {
            return unavailable(orderId, "조회 응답 reference 불일치");
        }
        return switch (data.status()) {
            case POSTED -> toPostedLookup(orderId, data);
            case REJECTED -> new LedgerLookupResult.Rejected(data.transactionId(), data.rejectReason());
            default -> unavailable(orderId, "알 수 없는 거래 상태: " + data.status());
        };
    }

    private static LedgerLookupResult toPostedLookup(UUID orderId, LedgerTransactionResponse data) {
        if (data.entries() == null || data.entries().isEmpty()) {
            return unavailable(orderId, "POSTED 거래에 항목 없음");
        }
        List<LedgerLookupResult.Entry> entries = new ArrayList<>(data.entries().size());
        for (LedgerTransactionResponse.Entry entry : data.entries()) {
            if (entry == null
                    || entry.ownerType() == null
                    || entry.currency() == null
                    || entry.direction() == null
                    || entry.amount() == null
                    || entry.amount() <= 0
                    || entry.entryType() == null) {
                return unavailable(orderId, "POSTED 거래 항목 값 이상");
            }
            entries.add(new LedgerLookupResult.Entry(
                    entry.ownerType(),
                    entry.walletId(),
                    entry.accountType(),
                    entry.currency(),
                    entry.direction(),
                    entry.amount(),
                    entry.entryType()
            ));
        }
        return new LedgerLookupResult.Posted(data.transactionId(), entries);
    }

    private static LedgerLookupResult unavailable(UUID orderId, String detail) {
        log.error("원장 거래 단건 조회 응답이 계약과 다릅니다: orderId={}, detail={}", orderId, detail);
        return new LedgerLookupResult.Unavailable(detail);
    }

    /**
     * 실패 응답을 분류합니다. 재시도 신호 → 4xx 거절 여부 → 상태 코드 순서로 봅니다.
     * 다시 보내서 풀릴 수 있으면 Unknown, 다시 보내도 같으면 Invalid입니다.
     */
    private static LedgerPostResult classify(LedgerPosting posting, FeignException e) {
        // 재시도 신호를 상태 코드보다 먼저 본다. Retry-After가 붙은 응답(3xx·429·503 등)은 Feign이
        // RetryableException으로 주는데, 429처럼 4xx여도 "지금은 처리하지 않았으니 나중에 다시 보내라"는 뜻이다.
        boolean retryable = e instanceof RetryableException;
        if (retryable || !FeignErrors.isRejected(e)) {
            // 따라가지 않은 리다이렉트(3xx)는 원장 거래 등록까지 가지 못한 설정 오류라 다시 보내도 같다.
            if (!retryable && e.status() >= 300 && e.status() < 400) {
                return invalid(posting, InvalidKind.MALFORMED_RESPONSE, "HTTP " + e.status());
            }
            String cause = cause(e);
            log.warn("원장 결과를 확인하지 못했습니다: orderId={}, cause={}", posting.orderId(), cause);
            return new LedgerPostResult.Unknown(cause);
        }
        int status = e.status();
        if (status == 422) {
            String code = FeignErrors.errorCode(e).orElse(null);
            if (code != null && code.startsWith(REJECTED_PREFIX)) {
                return new LedgerPostResult.Rejected(code);
            }
            return invalid(posting, InvalidKind.MALFORMED_RESPONSE, "422 오류 코드 이상: " + code);
        }
        if (status == 400) {
            return invalid(posting, InvalidKind.BAD_REQUEST, "HTTP 400");
        }
        if (status == 409) {
            return invalid(posting, InvalidKind.CONFLICT, "HTTP 409");
        }
        return invalid(posting, InvalidKind.MALFORMED_RESPONSE, "HTTP " + status);
    }

    /** 결과를 모르는 이유. 타임아웃·연결 실패는 응답이 없어 status가 음수다 */
    private static String cause(FeignException e) {
        if (e instanceof DecodeException) {
            return "응답 본문 해석 실패(HTTP " + e.status() + ")";
        }
        return e.status() < 0 ? e.getClass().getSimpleName() : "HTTP " + e.status();
    }

    /** 다시 보내도 풀리지 않는 응답이라 사람이 확인해야 한다. 원인을 찾을 수 있게 error로 남긴다 */
    private static LedgerPostResult invalid(LedgerPosting posting, InvalidKind kind, String detail) {
        log.error("원장이 처리할 수 없는 응답을 줬습니다: orderId={}, kind={}, detail={}",
                posting.orderId(), kind, detail);
        return new LedgerPostResult.Invalid(kind);
    }
}
