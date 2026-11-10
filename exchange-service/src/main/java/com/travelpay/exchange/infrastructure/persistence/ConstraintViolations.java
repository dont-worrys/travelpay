package com.travelpay.exchange.infrastructure.persistence;

import org.hibernate.exception.ConstraintViolationException;

/** DB 제약 위반 예외에서 어떤 제약이 깨졌는지 확인합니다. */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * 예외 원인 중 Hibernate가 해석한 제약 이름이 주어진 이름 중 하나와 같은지 확인합니다.
     * 제약 이름을 알 수 없는 실패(자릿수 초과 등)는 false입니다.
     */
    public static boolean violates(Throwable e, String... constraintNames) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String violated = violation.getConstraintName();
                for (String name : constraintNames) {
                    if (name.equalsIgnoreCase(violated)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }
}
