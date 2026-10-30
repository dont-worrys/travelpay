package com.travelpay.user.domain.model.entity;

import java.time.Instant;

import com.travelpay.user.domain.model.vo.TermsDocumentId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게시한 약관 원본이며 내용 변경은 새로운 버전으로 저장합니다.
 * 같은 약관 코드는 동일한 시행 시각에 하나의 버전만 가집니다.
 */
@Entity
@Table(name = "terms_documents", uniqueConstraints = @UniqueConstraint(
        name = "uk_terms_documents_code_effective_at", columnNames = {"terms_code", "effective_at"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsDocument {

    @EmbeddedId
    private TermsDocumentId id;

    @Column(name = "title", nullable = false, updatable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "is_required", nullable = false, updatable = false)
    private boolean required;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    private TermsDocument(TermsDocumentId id, String title, String content, boolean required, Instant effectiveAt) {
        if (id == null) {
            throw new IllegalArgumentException("약관 식별자는 비어 있을 수 없습니다.");
        }
        if (effectiveAt == null) {
            throw new IllegalArgumentException("약관 시행 시각은 비어 있을 수 없습니다.");
        }
        this.id = id;
        this.title = requireTitle(title);
        this.content = requireContent(content);
        this.required = required;
        this.effectiveAt = effectiveAt;
    }

    public static TermsDocument publish(
            TermsDocumentId id, String title, String content, boolean required, Instant effectiveAt) {
        return new TermsDocument(id, title, content, required, effectiveAt);
    }

    /** 이 버전의 시행 시각 도달 여부이며, 코드별 최신 버전 선택은 조회 로직에서 처리합니다. */
    public boolean hasTakenEffect(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("시행 여부를 확인할 시각은 비어 있을 수 없습니다.");
        }
        return !at.isBefore(effectiveAt);
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("약관 제목은 비어 있을 수 없습니다.");
        }
        var normalizedTitle = title.strip();
        if (normalizedTitle.codePointCount(0, normalizedTitle.length()) > 200
                || normalizedTitle.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("약관 제목은 제어 문자가 없는 200자 이하의 값이어야 합니다.");
        }
        return normalizedTitle;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank() || content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("약관 본문은 비어 있지 않고 널 문자를 포함하지 않아야 합니다.");
        }
        return content;
    }
}
