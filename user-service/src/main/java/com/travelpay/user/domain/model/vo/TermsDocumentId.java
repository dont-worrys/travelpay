package com.travelpay.user.domain.model.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** 약관 종류와 버전의 조합으로 원본 하나를 식별하는 값 객체입니다. */
@Embeddable
public record TermsDocumentId(
        @Column(name = "terms_code", nullable = false, updatable = false, length = 40)
        String termsCode,
        @Column(name = "terms_version", nullable = false, updatable = false, length = 40)
        String termsVersion
) {

    public TermsDocumentId {
        termsCode = requirePart(termsCode, "약관 코드");
        termsVersion = requirePart(termsVersion, "약관 버전");
    }

    private static String requirePart(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("올바른 " + label + " 값이 필요합니다.");
        }
        var normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > 40
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " 값은 제어 문자가 없는 40자 이하의 값이어야 합니다.");
        }
        return normalized;
    }
}
