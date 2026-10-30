package com.travelpay.bank.domain.transaction;

/** uk_bank_transactions_request 위반: 같은 requestId가 먼저 기록됐다. */
public class BankDuplicateRequestException extends RuntimeException {

    public BankDuplicateRequestException(Throwable cause) {
        super("같은 requestId의 거래가 이미 기록되어 있습니다.", cause);
    }
}
