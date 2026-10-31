package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.wallet.application.charge.LedgerTransactionClient;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * 원장 거래 등록 응답 해석. 원장 응답 내용은 결과·로그에 싣지 않는다 (422의 error.code만 사용).
 * - 201·200 + transactionId·status POSTED: Posted
 * - 422 + error.code LEDGER_REJECTED_*: Rejected (같은 reference로 다시 보내도 같은 422)
 * - 5xx·타임아웃·연결 실패: Unknown
 * - 409·400·그 밖의 4xx·형식 이상: Invalid
 */
@Component
@RequiredArgsConstructor
public class LedgerTransactionClientAdapter implements LedgerTransactionClient {

    private static final int UNPROCESSABLE = 422;
    private static final String REJECTED_PREFIX = "LEDGER_REJECTED_";
    private static final String POSTED = "POSTED";

    private final LedgerTransactionFeignClient feignClient;
    private final JsonMapper jsonMapper;

    @Override
    public LedgerPostResult postCharge(UUID chargeId, UUID userId, UUID walletId, long amount) {
        LedgerApiResponse<LedgerTransactionResponse> response;
        try {
            response = feignClient.register(RegisterLedgerTransactionRequest.charge(chargeId, userId, walletId,
                    amount));
        } catch (FeignException e) {
            return classify(e);
        }
        if (response == null || !response.success() || response.data() == null
                || response.data().transactionId() == null || !POSTED.equals(response.data().status())) {
            return new LedgerPostResult.Invalid("ledger response invalid: body");
        }
        return new LedgerPostResult.Posted(response.data().transactionId());
    }

    private LedgerPostResult classify(FeignException e) {
        if (e instanceof RetryableException || e.status() >= 500) {
            return new LedgerPostResult.Unknown(e.getClass().getName());
        }
        if (e.status() == UNPROCESSABLE) {
            String code = rejectedCode(e);
            if (code != null) {
                return new LedgerPostResult.Rejected(code);
            }
        }
        return new LedgerPostResult.Invalid(e.getClass().getName());
    }

    private String rejectedCode(FeignException e) {
        try {
            LedgerApiResponse<?> body = jsonMapper.readValue(e.contentUTF8(), LedgerApiResponse.class);
            if (body == null || body.success() || body.error() == null || body.error().code() == null
                    || !body.error().code().startsWith(REJECTED_PREFIX)) {
                return null;
            }
            return body.error().code();
        } catch (JacksonException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
