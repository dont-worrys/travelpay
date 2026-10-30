package com.travelpay.user.presentation.dto;

import java.time.Instant;
import java.util.List;

import com.travelpay.user.application.dto.TermsResult;

/** 현재 약관 조회 API의 응답 데이터입니다. */
public record TermsResponse(List<TermItem> terms) {

    public static TermsResponse from(List<TermsResult> results) {
        return new TermsResponse(results.stream()
                .map(TermItem::from)
                .toList());
    }

    /** API에 노출할 약관 한 건의 정보입니다. */
    public record TermItem(
            String termsCode,
            String termsVersion,
            String title,
            String content,
            boolean isRequired,
            Instant effectiveAt
    ) {

        private static TermItem from(TermsResult result) {
            return new TermItem(result.termsCode(), result.termsVersion(), result.title(),
                    result.content(), result.required(), result.effectiveAt());
        }
    }
}
