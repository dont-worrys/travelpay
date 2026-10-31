package com.travelpay.user.domain.model.vo;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/** 회원 ID와 약관 코드·버전을 묶은 동의 기록의 식별자입니다. */
@Embeddable
public record UserConsentId(
        @Column(name = "user_id", nullable = false, updatable = false)
        UUID userId,
        @Embedded
        TermsDocumentId termsDocumentId
) {

    public UserConsentId {
        if (userId == null) {
            throw new IllegalArgumentException("동의한 회원의 식별자가 필요합니다.");
        }
        if (termsDocumentId == null) {
            throw new IllegalArgumentException("동의한 약관의 식별자가 필요합니다.");
        }
    }
}
