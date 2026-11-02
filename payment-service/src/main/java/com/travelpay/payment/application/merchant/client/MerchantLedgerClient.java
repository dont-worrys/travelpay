package com.travelpay.payment.application.merchant.client;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.util.UUID;

public interface MerchantLedgerClient {

    void openAccounts(UUID walletId, CurrencyCode currency);

    /** 원장 지갑 계정 중 해당 통화 계정의 잔액 */
    long getBalance(UUID walletId, CurrencyCode currency);
}
