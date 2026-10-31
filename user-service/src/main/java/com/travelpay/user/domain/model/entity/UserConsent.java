package com.travelpay.user.domain.model.entity;

import java.time.Instant;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.domain.model.vo.UserConsentId;
import com.travelpay.user.common.error.UserErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원이 동의한 약관 버전과 시각이며 새 버전 동의는 새 기록으로 남깁니다. */
@Entity
@Table(name = "user_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @EmbeddedId
    private UserConsentId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_user_consents_user"))
    private User user;

    @MapsId("termsDocumentId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "terms_code", referencedColumnName = "terms_code",
                    nullable = false),
            @JoinColumn(name = "terms_version", referencedColumnName = "terms_version",
                    nullable = false)
    }, foreignKey = @ForeignKey(name = "fk_user_consents_terms_document"))
    private TermsDocument termsDocument;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    private UserConsent(User user, TermsDocument termsDocument, Instant agreedAt) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("동의할 회원의 식별자가 필요합니다.");
        }
        if (termsDocument == null || termsDocument.getId() == null) {
            throw new IllegalArgumentException("동의할 약관 원본이 필요합니다.");
        }
        if (agreedAt == null) {
            throw new IllegalArgumentException("약관 동의 시각이 필요합니다.");
        }
        if (!termsDocument.hasTakenEffect(agreedAt)) {
            throw new BusinessException(UserErrorCode.TERMS_CONSENT_INVALID);
        }
        this.id = new UserConsentId(user.getId(), termsDocument.getId());
        this.user = user;
        this.termsDocument = termsDocument;
        this.agreedAt = agreedAt;
    }

    /** 식별자가 부여된 회원과 시행된 약관에 대한 동의를 서버 시각으로 기록합니다. */
    public static UserConsent agreeTo(User user, TermsDocument termsDocument, Instant agreedAt) {
        return new UserConsent(user, termsDocument, agreedAt);
    }
}
