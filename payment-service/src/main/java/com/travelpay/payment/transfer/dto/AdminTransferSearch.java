package com.travelpay.payment.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.payment.transfer.domain.TransferStatus;

/**
 * 관리자 송금 목록 조회 조건입니다. 모든 값은 선택이며 null이면 해당 조건을 적용하지 않습니다.
 *
 * @param from     생성 시각 하한(포함)
 * @param to       생성 시각 상한(미포함)
 * @param currency 통화 코드(KRW·JPY·CNY)
 * @param status   송금 상태
 * @param userId   송금인 또는 수취인 회원 ID
 */
public record AdminTransferSearch(
        Instant from,
        Instant to,
        String currency,
        TransferStatus status,
        UUID userId
) {
}
