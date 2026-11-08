package com.travelpay.user.domain.model.entity;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.vo.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 로그인 수단과 무관하게 유지되는 회원의 공통 정보입니다. */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_phone", columnNames = "phone"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "real_name", nullable = false, length = 40)
    private String realName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private AuthRole role;

    // 로그인 때 세션에 복사해 두고, 로그아웃을 뺀 로그인이 필요한 요청마다 비교함
    // 올릴 때는 UPDATE 문(+1)만 씀. 엔티티를 저장할 때 읽어 둔 예전 값으로 덮어쓰지 않도록 수정 대상에서 뺌
    @Column(name = "session_version", nullable = false, updatable = false)
    private long sessionVersion;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /** 학습용 번호 형식 검사 완료 시각이며, 실제 번호 소유 인증을 뜻하지 않습니다. */
    @Column(name = "phone_verified_at", nullable = false)
    private Instant phoneVerifiedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private User(String realName, String phone, Instant phoneCheckedAt) {
        if (phoneCheckedAt == null) {
            throw new IllegalArgumentException("휴대폰 번호 형식 검사 시각이 필요합니다.");
        }
        this.realName = requireRealName(realName);
        this.phone = requirePhone(phone);
        this.status = UserStatus.ACTIVE;
        this.role = AuthRole.USER;
        this.phoneVerifiedAt = phoneCheckedAt;
    }

    /** 국내 번호 형식과 서버의 형식 검사 시각으로 일반 회원을 생성합니다. */
    public static User register(String realName, String phone, Instant phoneCheckedAt) {
        return new User(realName, phone, phoneCheckedAt);
    }

    /** 관리자의 회원 정지·해제입니다. 종료된 계정은 바꿀 수 없고, 상태가 실제로 바뀌었을 때만 true입니다. */
    public boolean changeStatus(UserStatus requested) {
        if (requested != UserStatus.ACTIVE && requested != UserStatus.SUSPENDED) {
            throw new IllegalArgumentException("관리자는 활동 또는 정지 상태만 지정할 수 있습니다.");
        }
        if (status == UserStatus.CLOSED) {
            throw new BusinessException(UserErrorCode.CLOSED_ACCOUNT_UNCHANGEABLE);
        }
        if (status == requested) {
            return false;
        }
        status = requested;
        return true;
    }

    private static String requireRealName(String realName) {
        if (realName == null || realName.isBlank()) {
            throw new IllegalArgumentException("성함을 입력해 주세요.");
        }
        var normalizedRealName = realName.strip();
        if (normalizedRealName.codePointCount(0, normalizedRealName.length()) > 40) {
            throw new IllegalArgumentException("성함은 40자 이내로 입력해 주세요.");
        }
        return normalizedRealName;
    }

    private static String requirePhone(String phone) {
        if (phone == null || !phone.matches("010-[0-9]{4}-[0-9]{4}")) {
            throw new IllegalArgumentException("휴대폰 번호는 010-1234-5678 형식으로 입력해 주세요.");
        }
        return phone;
    }
}
