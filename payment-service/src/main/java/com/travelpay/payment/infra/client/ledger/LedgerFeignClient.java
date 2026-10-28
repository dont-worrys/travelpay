package com.travelpay.payment.infra.client.ledger;

import com.travelpay.payment.application.payment.client.ledger.LedgerClient;
import com.travelpay.payment.application.payment.client.ledger.LedgerResult;
import com.travelpay.payment.application.payment.client.ledger.LedgerTransactionCommand;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("!prod")
public class LedgerFeignClient implements LedgerClient {

    private static final long FAKE_BALANCE = 1_000_000L;

    @Override
    public LedgerResult createTransaction(LedgerTransactionCommand command) {
        if (command.amount() > FAKE_BALANCE) {
            return LedgerResult.declined("잔액이 부족합니다.");
        }
        UUID transactionId = UUID.nameUUIDFromBytes(("ledger-tx:" + command.referenceId()).getBytes());
        return LedgerResult.approved(transactionId);
    }
}
