package com.travelpay.payment.transfer.dto;

import java.util.UUID;

/**
 * 송금 전 수취인 확인 결과입니다. 이름은 마스킹한 값만 담고, 지갑 ID·지갑 상태 같은 내부 정보는 포함하지 않습니다.
 */
public record RecipientResponse(
        UUID receiverId,
        String receiverName,
        boolean transferable
) {
}
