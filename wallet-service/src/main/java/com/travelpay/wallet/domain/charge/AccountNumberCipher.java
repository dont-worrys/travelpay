package com.travelpay.wallet.domain.charge;

/** 계좌번호 암호화. 같은 평문도 매번 다른 암호문이 나오므로 암호문끼리 비교하지 않는다 */
public interface AccountNumberCipher {

    String encrypt(String accountNumber);

    String decrypt(String accountNumberEnc);
}
