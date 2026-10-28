package com.travelpay.ledger.domain.account;

public enum AccountType {
    WALLET,               // 사용자 지갑
    CHARGE_SOURCE,        // 충전금 출처
    FX_POSITION,          // 환전 계정
    FEE_REVENUE,          // 수수료 수익
    MERCHANT_SETTLEMENT   // 가맹점 정산금
}
