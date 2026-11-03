package com.travelpay.payment.application.transfer.dto;

import java.util.UUID;

/** 송금 전 수취인 확인 결과입니다. 이름은 마스킹한 값만 담습니다. */
public record RecipientResult(UUID receiverId, String receiverName, boolean transferable) {
}
