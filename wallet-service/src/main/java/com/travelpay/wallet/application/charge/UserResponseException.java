package com.travelpay.wallet.application.charge;

/** 재시도로 풀리지 않는 회원 서비스 응답(그 밖의 4xx, 형식 이상). 공통 처리기가 500으로 응답한다 */
public class UserResponseException extends RuntimeException {

    public UserResponseException() {
        this(null);
    }

    public UserResponseException(Throwable cause) {
        super("회원 서비스 응답을 처리할 수 없습니다.", cause);
    }

    /** 로그용. 원인 예외가 있으면 그 클래스명 */
    public String causeClassName() {
        return getCause() != null ? getCause().getClass().getName() : getClass().getName();
    }
}
