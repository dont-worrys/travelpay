package com.travelpay.payment.infra.client.transfer;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.transfer.client.LedgerTransactionRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 원장 서비스의 거래 등록 내부 API입니다. 응답 해석은 {@link TransferLedgerClientAdapter}가 맡습니다.
 * 새로 기록하면 201, 같은 reference·같은 내용의 재요청은 200, 확정 거절은 422(LEDGER_REJECTED_*),
 * 같은 reference·다른 내용은 409(LEDGER_REFERENCE_CONFLICT), 형식 오류는 400입니다.
 * contextId로 설정 키(transfer-ledger)를 나누며, 자동 재시도는 {@link TransferLedgerFeignConfig}에서 끕니다.
 * 주소는 결제 원장 클라이언트와 같은 travelpay.client.ledger.url(LEDGER_SERVICE_URL)을 쓰고, 비어 있으면 Eureka에서 찾습니다.
 */
@FeignClient(name = "ledger-service", contextId = "transfer-ledger", url = "${travelpay.client.ledger.url:}",
        path = "/internal/v1/ledger/transactions", configuration = TransferLedgerFeignConfig.class)
public interface TransferLedgerFeignClient {

    @PostMapping
    ApiResponse<LedgerRegisterResponse> register(@RequestBody LedgerTransactionRequest request);
}
