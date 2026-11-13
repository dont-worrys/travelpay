package com.travelpay.payment.transfer.client;

/**
 * 원장 거래 등록이 확정 결과(POSTED·확정 거절)로 끝나지 않았을 때 던집니다. 송금은 어느 경우든 PROCESSING으로 남습니다.
 * 메시지에는 HTTP 상태와 오류 코드만 담고 응답 본문은 담지 않습니다.
 */
public class TransferLedgerException extends RuntimeException {

    public enum Kind {
        /** 타임아웃·연결 실패·5xx·해석할 수 없는 성공 응답. 원장이 처리했을 수 있어 같은 reference 재전송으로 확인합니다. */
        RESULT_UNKNOWN,
        /**
         * 확정 거절이 아닌 4xx(409 reference 충돌, 400 형식 오류, 알 수 없는 422 코드, 경로 오류 404 등).
         * 원장에 반영되지 않았지만 송금 요청을 잘못 만든 버그이거나 계약 불일치라 실패로 단정하지 않습니다.
         */
        REQUEST_NOT_ACCEPTED,
        /**
         * 원장 서킷이 열려 있어 원장에 보내지 않았습니다. 원장에 닿지 않았으므로 결과 모름 시도 횟수에 넣지 않고,
         * 복구 스케줄러가 대기 시간 뒤 다시 보냅니다.
         */
        NOT_SENT
    }

    private final Kind kind;

    private TransferLedgerException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static TransferLedgerException resultUnknown(String message, Throwable cause) {
        return new TransferLedgerException(Kind.RESULT_UNKNOWN, message, cause);
    }

    public static TransferLedgerException requestNotAccepted(String message, Throwable cause) {
        return new TransferLedgerException(Kind.REQUEST_NOT_ACCEPTED, message, cause);
    }

    public static TransferLedgerException notSent(String message) {
        return new TransferLedgerException(Kind.NOT_SENT, message, null);
    }

    public Kind getKind() {
        return kind;
    }
}
