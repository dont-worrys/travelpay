package com.travelpay.payment.transfer.dto;

import java.util.UUID;

/**
 * 송금 전 수취인 확인 결과입니다. 지갑 ID·지갑 상태 같은 내부 정보는 포함하지 않고 송금 가능 여부만 알립니다.
 * 회원 이름 등은 user-service의 내부 회원 조회 API가 확정되면 추가를 검토합니다.
 */
public record RecipientResponse(
        UUID receiverId,
        boolean transferable
) {
}
