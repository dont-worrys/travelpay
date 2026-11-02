package com.travelpay.wallet.infra.client.bank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * 은행 응답 유실 재현용 장애 주입 (로컬 검증 전용).
 * travelpay.client.bank.fault-injection.mode가 LOST·DELAY면 출금 첫 시도(attempt_count 0)에만
 * Mock-Fault 헤더 값을 돌려준다. 재시도에도 붙이면 매번 유실되어 MANUAL_REVIEW로 끝나므로 첫 시도에만 붙인다.
 * 비우거나 공백이면 끔. 그 외 값이거나 prod 프로필에서 값이 있으면 기동 실패.
 */
@Slf4j
@Component
public class BankFaultInjection {

    public static final String HEADER = "Mock-Fault";
    static final String MODE_KEY = "travelpay.client.bank.fault-injection.mode";

    private final Mode mode;

    public BankFaultInjection(@Value("${" + MODE_KEY + ":}") String mode, Environment environment) {
        this.mode = parse(mode);
        if (this.mode != null && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(MODE_KEY + " must be empty when prod profile is active");
        }
    }

    /** 출금 요청에 실을 Mock-Fault 값. 끔이거나 첫 시도가 아니면 null */
    public String withdrawHeader(UUID chargeId, int attemptCount) {
        if (mode == null || attemptCount != 0) {
            return null;
        }
        log.warn("은행 장애 주입 헤더 추가 chargeId={} mode={}", chargeId, mode);
        return mode.name();
    }

    private static Mode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (Mode mode : Mode.values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(MODE_KEY + " must be LOST or DELAY: " + value);
    }

    private enum Mode {
        LOST,
        DELAY
    }
}
