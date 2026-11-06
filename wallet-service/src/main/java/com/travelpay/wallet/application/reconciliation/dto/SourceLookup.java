package com.travelpay.wallet.application.reconciliation.dto;

import java.util.Objects;

/**
 * 한 소스(은행 출금·환불·원장)에서 한 건을 찾은 결과.
 * NotFound는 상대가 "없음"(404 + 지정 오류 코드)이라고 답했거나 기간 목록에 없어 없음으로 본 경우뿐이다.
 * 그 밖의 조회 실패(타임아웃·5xx·연결 실패·응답 이상)는 Unavailable이다.
 */
public sealed interface SourceLookup<T> {

    static <T> SourceLookup<T> found(T record) {
        return new Found<>(record);
    }

    static <T> SourceLookup<T> notFound() {
        return new NotFound<>();
    }

    static <T> SourceLookup<T> unavailable(String reason) {
        return new Unavailable<>(reason);
    }

    record Found<T>(T record) implements SourceLookup<T> {
        public Found {
            Objects.requireNonNull(record, "record");
        }
    }

    record NotFound<T>() implements SourceLookup<T> {
    }

    record Unavailable<T>(String reason) implements SourceLookup<T> {
    }
}
