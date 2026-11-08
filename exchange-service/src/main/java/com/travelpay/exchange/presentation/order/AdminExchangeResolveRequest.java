package com.travelpay.exchange.presentation.order;

import jakarta.validation.constraints.Size;

public record AdminExchangeResolveRequest(
        @Size(max = 255, message = "메모는 255자 이하여야 합니다.")
        String memo
) {
    public static String memoOf(AdminExchangeResolveRequest request) {
        return request != null ? request.memo() : null;
    }
}
