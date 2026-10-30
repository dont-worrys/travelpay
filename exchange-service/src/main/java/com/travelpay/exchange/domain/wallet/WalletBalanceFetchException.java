package com.travelpay.exchange.domain.wallet;

/** 지갑 잔액을 가져오지 못한 경우입니다. */
public class WalletBalanceFetchException extends RuntimeException {

    public WalletBalanceFetchException(String message) {
        super(message);
    }
}
