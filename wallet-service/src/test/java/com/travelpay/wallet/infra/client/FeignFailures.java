package com.travelpay.wallet.infra.client;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 어댑터 테스트용 Feign 실패. Feign이 실제로 던지는 형태로 만든다 */
public final class FeignFailures {

    private FeignFailures() {
    }

    /** 상대가 상태 코드와 공통 실패 본문으로 응답함 */
    public static FeignException error(int status, String code) {
        return response(status, "{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"m\",\"details\":[]}}");
    }

    /** 상대가 상태 코드와 주어진 본문으로 응답함 */
    public static FeignException response(int status, String body) {
        Response response = Response.builder()
                .status(status)
                .request(request())
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("Client#call", response);
    }

    /** 응답 대기 시간 초과 */
    public static FeignException timeout() {
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request());
    }

    /** 연결 거부 */
    public static FeignException connectionRefused() {
        return new RetryableException(-1, "Connection refused", Request.HttpMethod.POST,
                new ConnectException("Connection refused"), (Long) null, request());
    }

    /** 2xx 본문 해석 실패 */
    public static FeignException undecodable() {
        return new DecodeException(200, "본문을 해석할 수 없습니다.", request());
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.POST, "/internal/test", Map.of(), null,
                StandardCharsets.UTF_8, null);
    }
}
