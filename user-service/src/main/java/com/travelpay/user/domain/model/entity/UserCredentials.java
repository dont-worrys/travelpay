package com.travelpay.user.domain.model.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 회원의 이메일·비밀번호 로그인 수단이며 소셜 전용 회원에게는 생성하지 않습니다. */
@Entity
@Table(name = "user_credentials", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_credentials_login_email", columnNames = "login_email"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCredentials {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_user_credentials_user"))
    private User user;

    @Column(name = "login_email", nullable = false, updatable = false, length = 254)
    private String loginEmail;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private UserCredentials(User user, String loginEmail, String passwordHash, Instant passwordSetAt) {
        if (user == null) {
            throw new IllegalArgumentException("로그인 수단을 등록할 회원은 비어 있을 수 없습니다.");
        }
        if (passwordSetAt == null) {
            throw new IllegalArgumentException("비밀번호 설정 시각은 비어 있을 수 없습니다.");
        }
        this.user = user;
        this.loginEmail = requireLoginEmail(loginEmail);
        this.passwordHash = requirePasswordHash(passwordHash);
        this.passwordChangedAt = passwordSetAt;
    }

    /** PasswordEncoder로 변환한 해시와 서버 시각으로 회원의 자체 로그인 수단을 생성합니다. */
    public static UserCredentials createFor(User user, String loginEmail, String passwordHash, Instant passwordSetAt) {
        return new UserCredentials(user, loginEmail, passwordHash, passwordSetAt);
    }

    private static String requireLoginEmail(String loginEmail) {
        if (loginEmail == null) {
            throw new IllegalArgumentException("로그인 이메일은 비어 있을 수 없습니다.");
        }
        var normalizedEmail = loginEmail.strip().toLowerCase(Locale.ROOT);
        if (normalizedEmail.length() > 254 || !normalizedEmail.matches("[^\\s@]+@[^\\s@]+")
                || normalizedEmail.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("로그인 이메일은 기본 이메일 형식을 갖춘 254자 이하의 값이어야 합니다.");
        }
        return normalizedEmail;
    }

    private static String requirePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255) {
            throw new IllegalArgumentException("비밀번호 해시는 비어 있지 않은 255자 이하의 값이어야 합니다.");
        }
        int prefixEnd = passwordHash.indexOf('}');
        if (!passwordHash.startsWith("{") || prefixEnd <= 1
                || passwordHash.substring(1, prefixEnd).isBlank()
                || "noop".equalsIgnoreCase(passwordHash.substring(1, prefixEnd))
                || passwordHash.substring(prefixEnd + 1).isBlank()) {
            throw new IllegalArgumentException("비밀번호는 해시로 변환된 저장 형식이어야 합니다.");
        }
        return passwordHash;
    }
}
