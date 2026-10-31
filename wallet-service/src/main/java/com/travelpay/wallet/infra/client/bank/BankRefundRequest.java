package com.travelpay.wallet.infra.client.bank;

import java.util.UUID;

public record BankRefundRequest(String requestId, UUID originalTxId, long amount) {
}
