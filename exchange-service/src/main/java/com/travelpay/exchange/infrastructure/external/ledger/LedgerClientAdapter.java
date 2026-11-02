package com.travelpay.exchange.infrastructure.external.ledger;

import java.util.Objects;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult.InvalidKind;
import com.travelpay.exchange.domain.ledger.LedgerPosting;
import feign.FeignException;
import feign.RetryableException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 원장 거래 등록 응답을 4분류로 바꿉니다. 예외를 던지지 않습니다. 원장 응답 본문은 결과·로그에 싣지 않고 error.code만 봅니다.
 * - 201·200 + 거래 ID + POSTED: Posted
 * - 422 + LEDGER_REJECTED_*: Rejected (같은 reference로 다시 보내도 같은 422)
 * - 4xx로 거절되지 않은 실패(타임아웃·연결 실패·5xx·2xx인데 본문을 해석하지 못함): Unknown
 *   원장이 이미 기록했을 수 있으므로 실패로 끝내지 않고 같은 요청을 다시 보내 결과를 확인한다.
 * - 400: Invalid(BAD_REQUEST), 409: Invalid(CONFLICT)
 * - 그 밖의 4xx·따라가지 않은 3xx·본문은 읽었지만 내용이 계약과 다른 성공 응답: Invalid(MALFORMED_RESPONSE)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerClientAdapter implements LedgerClient {

    private static final String POSTED = "POSTED";
    private static final String REJECTED_PREFIX = "LEDGER_REJECTED_";

    private final LedgerFeignClient feignClient;

    @Override
    public LedgerPostResult post(LedgerPosting posting) {
        Objects.requireNonNull(posting, "posting");
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

    private static LedgerPostResult classify(LedgerPosting posting, FeignException e) {
        if (!FeignErrors.isRejected(e)) {
            // 따라가지 않은 리다이렉트(3xx)는 원장 거래 등록까지 가지 못한 설정 오류라 다시 보내도 같다.
            // Retry-After가 있는 3xx는 Feign이 RetryableException으로 주므로 다시 확인(Unknown)한다.
            if (e.status() >= 300 && e.status() < 400 && !(e instanceof RetryableException)) {
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
