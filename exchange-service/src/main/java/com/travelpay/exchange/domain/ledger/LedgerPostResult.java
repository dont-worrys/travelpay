package com.travelpay.exchange.domain.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * 원장 거래 등록 결과입니다. 원장 클라이언트는 HTTP 응답과 호출 실패를 예외 대신 이 네 가지로 돌려줍니다.
 */
public sealed interface LedgerPostResult {

    /** 201 신규 기록, 200 같은 요청 재전송. 원장 거래 ID를 받습니다. */
    record Posted(UUID transactionId) implements LedgerPostResult {

        public Posted {
            Objects.requireNonNull(transactionId, "transactionId");
        }
    }

    /** 422 확정 거절. 같은 reference로 다시 보내도 같은 거절입니다. rejectCode는 원장 error.code(LEDGER_REJECTED_*)입니다. */
    record Rejected(String rejectCode) implements LedgerPostResult {

        public Rejected {
            Objects.requireNonNull(rejectCode, "rejectCode");
        }
    }

    /** 타임아웃·5xx·연결 실패. 원장에 기록됐는지 알 수 없으므로 같은 요청을 다시 보내 확인합니다. */
    record Unknown(String cause) implements LedgerPostResult {
    }

    /** 다시 보내도 풀리지 않는 응답입니다. 자동으로 결론을 낼 수 없어 사람이 확인합니다. */
    record Invalid(InvalidKind kind) implements LedgerPostResult {

        public Invalid {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** 다시 보내도 풀리지 않는 응답의 종류입니다. */
    enum InvalidKind {
        /** 400: 우리 요청 형식 오류. 원장에 기록되지 않았으므로 고친 뒤 다시 보내면 기록할 수 있습니다. */
        BAD_REQUEST,
        /** 409: 같은 주문 ID로 다른 내용의 거래가 원장에 이미 있음 */
        CONFLICT,
        /** 그 밖의 4xx이거나 성공 응답인데 거래 ID가 없는 등 해석할 수 없는 응답 */
        MALFORMED_RESPONSE
    }
}
