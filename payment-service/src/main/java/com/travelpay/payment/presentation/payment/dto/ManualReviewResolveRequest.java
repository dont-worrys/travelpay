package com.travelpay.payment.presentation.payment.dto;

import jakarta.validation.constraints.Size;

/** 처리 요청 본문(선택). memo는 운영 기록용이며 고객에게 보이지 않습니다 */
public record ManualReviewResolveRequest(@Size(max = 255) String memo) {

    // 본문이 없거나 memo가 비어 있으면 null로 저장
    public static String memoOf(ManualReviewResolveRequest request) {
        if (request == null || request.memo() == null || request.memo().isBlank()) {
            return null;
        }
        return request.memo().strip();
    }
}
