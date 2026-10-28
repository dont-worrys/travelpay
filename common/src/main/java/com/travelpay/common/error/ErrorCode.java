package com.travelpay.common.error;

/** 서비스가 소유하는 API 오류 코드의 공통 규격입니다. */
public interface ErrorCode {

    String getCode();

    String getMessage();

    int getStatus();
}
