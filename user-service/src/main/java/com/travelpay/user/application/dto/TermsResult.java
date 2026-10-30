package com.travelpay.user.application.dto;

import java.time.Instant;

import com.travelpay.user.domain.model.entity.TermsDocument;

/** 표현 계층에 전달할 현재 약관의 조회 결과입니다. */
public record TermsResult(
        String termsCode,
        String termsVersion,
        String title,
        String content,
        boolean required,
        Instant effectiveAt
) {

    public static TermsResult from(TermsDocument terms) {
        var id = terms.getId();
        return new TermsResult(id.termsCode(), id.termsVersion(), terms.getTitle(),
                terms.getContent(), terms.isRequired(), terms.getEffectiveAt());
    }
}
