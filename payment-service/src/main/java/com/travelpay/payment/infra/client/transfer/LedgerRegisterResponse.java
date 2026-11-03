package com.travelpay.payment.infra.client.transfer;

import java.util.UUID;

/**
 * 원장 거래 등록 성공 응답의 data({transactionId, status})입니다. 성공 응답의 status는 POSTED만 옵니다.
 * 상태 값이 늘어도 역직렬화가 깨지지 않도록 문자열로 받습니다.
 */
public record LedgerRegisterResponse(UUID transactionId, String status) {
}
