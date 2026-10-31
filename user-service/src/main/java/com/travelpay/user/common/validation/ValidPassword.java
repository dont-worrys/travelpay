package com.travelpay.user.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** 회원가입·비밀번호 설정에서 사용할 ASCII 기반 새 비밀번호의 입력 규칙입니다. */
@Documented
@NotBlank
@Size(min = 10, max = 72)
// 공백을 제외한 ASCII 문자만 허용하며 영문·숫자·특수문자가 각각 필요합니다.
@Pattern(regexp = "(?=.*[A-Za-z])(?=.*[0-9])(?=.*\\p{Punct})[!-~]+")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
@Retention(RUNTIME)
public @interface ValidPassword {

    String message() default "비밀번호는 영문·숫자·특수문자만 사용하여 10~72자로 입력하고, 세 종류를 모두 포함해야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
