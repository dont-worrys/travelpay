package com.travelpay.user.presentation.dto;

/** 브라우저가 변경 요청에 사용할 CSRF 헤더 이름과 토큰입니다. */
public record CsrfTokenResponse(String headerName, String token) {

    public CsrfTokenResponse {
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalArgumentException("CSRF 헤더 이름은 비어 있을 수 없습니다.");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("CSRF 토큰은 비어 있을 수 없습니다.");
        }
    }

    @Override
    public String toString() {
        return "CsrfTokenResponse[headerName=" + headerName + ", token=[REDACTED]]";
    }
}
