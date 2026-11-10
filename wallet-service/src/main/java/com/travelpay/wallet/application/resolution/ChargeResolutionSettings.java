package com.travelpay.wallet.application.resolution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 충전 보정 설정. 값이 잘못되면 IllegalStateException으로 기동을 멈춘다 */
@Component
public class ChargeResolutionSettings {

    static final String NOT_FOUND_CONFIRM_AFTER_KEY = "travelpay.wallet.charge.resolution.not-found-confirm-after";

    private final Duration notFoundConfirmAfter;

    public ChargeResolutionSettings(@Value("${" + NOT_FOUND_CONFIRM_AFTER_KEY + ":10m}") String notFoundConfirmAfter) {
        Duration parsed;
        try {
            parsed = DurationFormatterUtils.detectAndParse(notFoundConfirmAfter, DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(NOT_FOUND_CONFIRM_AFTER_KEY + " 형식이 잘못되었습니다: "
                    + notFoundConfirmAfter, e);
        }
        if (parsed.isNegative() || parsed.isZero()) {
            throw new IllegalStateException(NOT_FOUND_CONFIRM_AFTER_KEY + "는 0보다 커야 합니다: " + notFoundConfirmAfter);
        }
        this.notFoundConfirmAfter = parsed;
    }

    /** MANUAL_REVIEW 전환 후 이 시간이 지난 NotFound만 "없음"으로 인정한다 */
    public Duration notFoundConfirmAfter() {
        return notFoundConfirmAfter;
    }
}
