package com.travelpay.wallet.domain.charge;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.util.Objects;

/**
 * 충전 출금 계좌. 계좌번호는 암호문만 보관하고 평문·마스킹 값은 저장하지 않는다.
 * 같은 평문도 암호문이 매번 달라 equals를 정의하지 않는다. 비교는 matches로 한다.
 */
@Getter
@Embeddable
public class BankAccount {

    private static final int MASK_VISIBLE = 4;

    @Column(name = "bank_code", nullable = false, updatable = false, length = 10)
    private String bankCode;

    @Column(name = "account_number_enc", nullable = false, updatable = false)
    private String accountNumberEnc;

    protected BankAccount() {
    }

    private BankAccount(String bankCode, String accountNumberEnc) {
        this.bankCode = Objects.requireNonNull(bankCode, "bankCode");
        this.accountNumberEnc = Objects.requireNonNull(accountNumberEnc, "accountNumberEnc");
    }

    /** 평문 계좌번호를 암호화해 만든다 */
    public static BankAccount encrypt(String bankCode, String accountNumber, AccountNumberCipher cipher) {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(cipher, "cipher");
        return new BankAccount(bankCode, cipher.encrypt(accountNumber));
    }

    /** 같은 멱등 키 재요청 비교: 은행 코드는 그대로, 계좌번호는 복호화해 비교 */
    public boolean matches(String bankCode, String accountNumber, AccountNumberCipher cipher) {
        Objects.requireNonNull(cipher, "cipher");
        return this.bankCode.equals(bankCode)
                && cipher.decrypt(accountNumberEnc).equals(accountNumber);
    }

    /** 응답 전용. 뒤 4자리만 남긴다 (예: ******1234) */
    public String maskedAccountNumber(AccountNumberCipher cipher) {
        Objects.requireNonNull(cipher, "cipher");
        String accountNumber = cipher.decrypt(accountNumberEnc);
        int hidden = Math.max(accountNumber.length() - MASK_VISIBLE, 0);
        if (hidden == 0) {
            return "*".repeat(accountNumber.length());
        }
        return "*".repeat(hidden) + accountNumber.substring(hidden);
    }
}
