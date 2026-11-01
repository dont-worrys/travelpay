package com.travelpay.exchange.infrastructure.external.ledger;

import java.util.Objects;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원장 거래 등록 응답을 4분류로 바꿉니다. 예외를 던지지 않습니다. 원장 응답 본문은 결과·로그에 싣지 않고 error.code만 봅니다.
 * - 201·200 + 거래 ID + POSTED: Posted
 * - 422 + LEDGER_REJECTED_*: Rejected (같은 reference로 다시 보내도 같은 422)
 * - 타임아웃·연결 실패·5xx: Unknown (기록됐는지 모름, 같은 요청을 다시 보냄)
 * - 400: Invalid(BAD_REQUEST), 409: Invalid(CONFLICT)
 * - 그 밖의 4xx·성공인데 형식이 맞지 않는 응답: Invalid(MALFORMED_RESPONSE)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerClientAdapter implements LedgerClient {

    private static final String POSTED = "POSTED";
    private static final String REJECTED_PREFIX = "LEDGER_REJECTED_";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LedgerFeignClient feignClient;

    @Override
    public LedgerPostResult post(LedgerPosting posting) {
        Objects.requireNonNull(posting, "posting");
        LedgerTransactionResponse response;
        try {
            response = feignClient.register(RegisterLedgerTransactionRequest.from(posting));
        } catch (DecodeException e) {
            // 2xx인데 본문을 해석하지 못함: 기록됐을 수 있지만 거래 ID를 알 수 없음
            return invalid(posting, InvalidKind.MALFORMED_RESPONSE, "응답 본문 해석 실패");
        } catch (FeignException e) {
            return classify(posting, e);
        }
        if (response == null || !response.success() || response.data() == null
                || response.data().transactionId() == null || !POSTED.equals(response.data().status())) {
            return invalid(posting, InvalidKind.MALFORMED_RESPONSE, "성공 응답 형식 이상");
        }
        return new LedgerPostResult.Posted(response.data().transactionId());
    }

    private static LedgerPostResult classify(LedgerPosting posting, FeignException e) {
        int status = e.status();
        // 타임아웃·연결 실패는 응답이 없어 status가 음수다
        if (e instanceof RetryableException || status < 0 || status >= 500) {
            String cause = status < 0 ? e.getClass().getSimpleName() : "HTTP " + status;
            log.warn("원장 결과를 확인하지 못했습니다: orderId={}, cause={}", posting.orderId(), cause);
            return new LedgerPostResult.Unknown(cause);
        }
        if (status == 422) {
            String code = errorCode(e);
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

    /** 다시 보내도 풀리지 않는 응답이라 사람이 확인해야 한다. 원인을 찾을 수 있게 error로 남긴다 */
    private static LedgerPostResult invalid(LedgerPosting posting, InvalidKind kind, String detail) {
        log.error("원장이 처리할 수 없는 응답을 줬습니다: orderId={}, kind={}, detail={}",
                posting.orderId(), kind, detail);
        return new LedgerPostResult.Invalid(kind);
    }

    /** 오류 응답 본문의 error.code. 해석하지 못하면 null */
    private static String errorCode(FeignException e) {
        String body = e.contentUTF8();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            LedgerTransactionResponse response = JSON.readValue(body, LedgerTransactionResponse.class);
            return response.error() == null ? null : response.error().code();
        } catch (JacksonException e2) {
            return null;
        }
    }
}
