package com.travelpay.payment.application.transfer.dto;

import java.util.UUID;

/**
 * 송금 요청 내용입니다. 송금인(인증된 요청자)과 Idempotency-Key는 요청 본문이 아니므로 서비스가 따로 받습니다.
 * 형식 검증(필수 값·양수·통화 형식·메모 길이)은 요청 DTO에서 끝난 값입니다.
 */
public record TransferCommand(UUID receiverId, Long amount, String currency, String memo) {
}
