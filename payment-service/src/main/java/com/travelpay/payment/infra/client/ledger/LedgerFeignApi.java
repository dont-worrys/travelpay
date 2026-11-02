package com.travelpay.payment.infra.client.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "ledger-service", contextId = "paymentLedgerApi", url = "${travelpay.client.ledger.url:}",
        path = "/internal/v1/ledger", configuration = LedgerFeignConfig.class)
public interface LedgerFeignApi {

    @PostMapping("/transactions")
    ApiResponse<TransactionResponse> register(@RequestBody RegisterRequest request);

    @PostMapping("/accounts")
    ApiResponse<List<AccountResponse>> openAccounts(@RequestBody OpenAccountsRequest request);

    @GetMapping("/accounts")
    ApiResponse<List<AccountResponse>> getAccounts(@RequestParam("walletId") UUID walletId);

    record RegisterRequest(
            String referenceType,
            UUID referenceId,
            UUID userId,
            UUID originalTransactionId,
            List<Entry> entries
    ) {
    }

    record Entry(
            String ownerType,
            UUID walletId,
            String currency,
            String direction,
            long amount,
            String entryType
    ) {
        static Entry user(UUID walletId, String currency, String direction, long amount, String entryType) {
            return new Entry("USER", walletId, currency, direction, amount, entryType);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionResponse(UUID transactionId, String status) {
    }

    record OpenAccountsRequest(UUID walletId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountResponse(UUID accountId, String currency, Long balance) {
    }
}
