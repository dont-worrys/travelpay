package com.travelpay.payment.infra.client.ledger;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class LedgerFeignConfig {

    @Bean
    public Retryer ledgerRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
