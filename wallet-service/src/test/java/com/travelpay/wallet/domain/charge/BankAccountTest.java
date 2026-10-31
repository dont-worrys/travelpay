package com.travelpay.wallet.domain.charge;

import com.travelpay.wallet.domain.Currency;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankAccountTest {

    /** 테스트용: 평문 앞에 표시만 붙이는 가짜 암호화 */
    private final AccountNumberCipher cipher = new AccountNumberCipher() {
        @Override
        public String encrypt(String accountNumber) {
            return "enc:" + accountNumber;
        }

        @Override
        public String decrypt(String accountNumberEnc) {
            return accountNumberEnc.substring("enc:".length());
        }
    };

    @Test
    void 계좌번호는_암호문으로만_보관한다() {
        BankAccount account = BankAccount.encrypt("004", "1234567890", cipher);

        assertEquals("004", account.getBankCode());
        assertEquals("enc:1234567890", account.getAccountNumberEnc());
    }

    @Test
    void 은행_코드와_복호화한_계좌번호가_모두_같아야_같은_계좌다() {
        BankAccount account = BankAccount.encrypt("004", "1234567890", cipher);

        assertTrue(account.matches("004", "1234567890", cipher));
        assertFalse(account.matches("088", "1234567890", cipher));
        assertFalse(account.matches("004", "1234567891", cipher));
    }

    @Test
    void 마스킹은_뒤_4자리만_남긴다() {
        assertEquals("******7890", BankAccount.encrypt("004", "1234567890", cipher).maskedAccountNumber(cipher));
        assertEquals("****", BankAccount.encrypt("004", "1234", cipher).maskedAccountNumber(cipher));
    }

    @Test
    void 금액_통화_계좌가_모두_같아야_같은_충전_요청이다() {
        ChargeRequest charge = ChargeRequest.create(UUID.randomUUID(), UUID.randomUUID(), 10_000, Currency.KRW,
                BankAccount.encrypt("004", "1234567890", cipher), "홍길동", UUID.randomUUID());

        assertTrue(charge.isSameRequest(10_000, Currency.KRW, "004", "1234567890", cipher));
        assertFalse(charge.isSameRequest(20_000, Currency.KRW, "004", "1234567890", cipher));
        assertFalse(charge.isSameRequest(10_000, Currency.JPY, "004", "1234567890", cipher));
        assertFalse(charge.isSameRequest(10_000, Currency.KRW, "004", "0000000000", cipher));
    }
}
