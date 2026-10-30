package com.travelpay.user.common.security;

import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.security.crypto.password.PasswordEncoder;

/** 평문 저장을 거절하고 BCrypt 형식을 확인한 뒤 기존 PasswordEncoder에 위임합니다. */
public final class ValidatingPasswordEncoder implements PasswordEncoder {

    private static final String BCRYPT_PREFIX = "{bcrypt}";
    private static final Pattern BCRYPT_BODY = Pattern.compile(
            "\\$2[aby]?\\$(?:0[4-9]|[12][0-9]|3[01])\\$[./0-9A-Za-z]{53}");

    private final PasswordEncoder delegate;

    public ValidatingPasswordEncoder(PasswordEncoder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "비밀번호 인코더가 필요합니다.");
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        validateStoredHash(encodedPassword);
        return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        validateStoredHash(encodedPassword);
        return delegate.upgradeEncoding(encodedPassword);
    }

    private void validateStoredHash(String encodedPassword) {
        if (encodedPassword != null && encodedPassword.regionMatches(true, 0, "{noop}", 0, 6)) {
            throw new IllegalArgumentException("비밀번호는 해시로 변환된 저장 형식이어야 합니다.");
        }
        if (encodedPassword != null && encodedPassword.startsWith(BCRYPT_PREFIX)
                && !BCRYPT_BODY.matcher(encodedPassword.substring(BCRYPT_PREFIX.length())).matches()) {
            throw new IllegalArgumentException("저장된 비밀번호 해시의 형식이 올바르지 않습니다.");
        }
    }
}
