package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.application.charge.dto.BankResult;

import java.util.UUID;

/**
 * 은행 출금·환불. 같은 requestId로 다시 보내면 은행이 처음 결과를 돌려준다.
 * 호출 실패도 예외가 아니라 BankResult(Unknown·Invalid)로 알린다.
 * withdraw의 chargeId·attemptCount는 요청 내용이 아니라 시도 정보다 (로컬 장애 주입 판단·로그용).
 */
public interface BankClient {

    BankResult withdraw(String requestId, String bankCode, String accountNumber, String holderName, long amount,
                        UUID chargeId, int attemptCount);

    BankResult refund(String requestId, UUID originalTxId, long amount);
}
