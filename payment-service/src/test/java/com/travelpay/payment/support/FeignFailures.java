package com.travelpay.payment.support;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import feign.Request;
import feign.RetryableException;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 응답을 받지 못한 Feign 실패. 실제 Feign처럼 상태 -1인 RetryableException으로 만든다 */
public final class FeignFailures {

    private static final Request REQUEST = Request.create(Request.HttpMethod.GET, "http://test/internal",
            Map.of(), null, UTF_8, null);

    private FeignFailures() {
    }

    public static RetryableException connectionRefused() {
        return noResponse(new ConnectException("Connection refused"));
    }

    public static RetryableException readTimeout() {
        return noResponse(new SocketTimeoutException("Read timed out"));
    }

    private static RetryableException noResponse(Exception cause) {
        return new RetryableException(-1, cause.getMessage(), Request.HttpMethod.GET, cause, (Long) null, REQUEST);
    }
}
