package com.travelpay.payment.transfer.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 송금 전 수취인 확인 결과입니다. 이름은 마스킹한 값만 담고, 지갑 ID·지갑 상태 같은 내부 정보는 포함하지 않습니다.
 */
public record RecipientResponse(
        @Schema(description = "수취인 회원 ID")
        UUID receiverId,
        @Schema(description = "마스킹한 수취인 이름", example = "홍*동")
        String receiverName,
        @Schema(description = "송금 가능 여부. 수취인 지갑이 활성 상태가 아니면 false")
        boolean transferable
) {
}
