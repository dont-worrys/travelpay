package com.travelpay.exchange.infrastructure.external.ledger;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 원장 거래 등록 응답의 data입니다. 바깥 형식 {success, data, error}는 공통 ApiResponse로 받습니다.
 * Feign이 Jackson으로 만들므로 public이어야 합니다.
 *
 * @param status POSTED 또는 REJECTED. 거절은 422 오류로 오므로 성공 응답은 POSTED여야 합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerTransactionResponse(UUID transactionId, String status) {
}
