package com.travelpay.wallet.infra.client.bank;

/** 계좌번호·예금주가 들어 있으므로 toString을 로그에 남기지 않는다 */
public record BankWithdrawalRequest(String requestId, String bankCode, String accountNumber, String holderName,
                                    long amount) {

    @Override
    public String toString() {
        return "BankWithdrawalRequest[requestId=" + requestId + "]";
    }
}
