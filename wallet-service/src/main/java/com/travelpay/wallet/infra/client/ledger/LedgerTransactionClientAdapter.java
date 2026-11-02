package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.LedgerTransactionClient;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 원장 거래 등록 응답 해석. 원장 응답 내용은 결과·로그에 싣지 않는다 (422의 error.code만 사용).
 * - 201·200 + transactionId·status POSTED: Posted (그 밖의 2xx 내용 이상은 Invalid)
 * - 422 + error.code LEDGER_REJECTED_*: Rejected (같은 reference로 다시 보내도 같은 422). 환불은 이 경우에만
 * - 그 밖의 4xx(409·400, 접두어가 다른 422 포함): Invalid (409는 같은 reference 거래가 이미 있다는 뜻이라 환불하면 이중 지급 위험)
 * - 4xx가 아닌 모든 실패(5xx·타임아웃·연결 실패·2xx 본문 해석 실패): Unknown.
 *   요청 전송 전 실패(EncodeException)·3xx도 Unknown이다. 어느 쪽이든 보상하지 않으므로 안전한 쪽으로 둔다
 */
@Component
@RequiredArgsConstructor
public class LedgerTransactionClientAdapter implements LedgerTransactionClient {

    private static final int UNPROCESSABLE = 422;
    private static final String REJECTED_PREFIX = "LEDGER_REJECTED_";
    private static final String POSTED = "POSTED";

    private final LedgerTransactionFeignClient feignClient;

    @Override
    public LedgerPostResult postCharge(UUID chargeId, UUID userId, UUID walletId, long amount) {
        ApiResponse<LedgerTransactionResponse> response;
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
        if (!FeignErrors.isRejected(e)) {
            return new LedgerPostResult.Unknown(e.getClass().getName());
        }
        if (e.status() == UNPROCESSABLE) {
            String code = FeignErrors.errorCode(e).orElse(null);
            if (code != null && code.startsWith(REJECTED_PREFIX)) {
                return new LedgerPostResult.Rejected(code);
            }
        }
        return new LedgerPostResult.Invalid(e.getClass().getName());
    }
}
