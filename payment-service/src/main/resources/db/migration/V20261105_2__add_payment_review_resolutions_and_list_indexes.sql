-- MANUAL_REVIEW 결제·환불을 운영자(MASTER)가 처리한 기록입니다. 상태가 바뀐 처리만 남깁니다(409·503으로 끝난 시도는 남기지 않음).
-- 처리 대상은 항상 MANUAL_REVIEW이므로 바뀌기 전 상태는 저장하지 않습니다.
-- refund_id: 환불 처리일 때만 있습니다. 없으면 결제 처리입니다. 결제 하나의 결제·환불 처리 기록은 payment_id로 함께 조회합니다.
-- review_reason: 멈췄던 사유. 처리 후 payments·refunds의 review_reason은 지워지므로(MANUAL_REVIEW일 때만 있음) 여기에 남깁니다.
-- ledger_status: 처리 근거가 된 원장 조회 결과. NOT_FOUND는 원장에 기록이 없음(ledger_transaction_id 없음)입니다.
-- to_status: 처리 대상(결제 또는 환불)의 바뀐 상태. 환불 처리로 바뀐 결제 상태는 payment_histories에 남습니다.
-- actor_id: 처리한 MASTER의 회원 ID입니다.
-- description: 시스템이 남기는 처리 설명(멈춘 사유, 원장 조회 결과, 처리 결과). memo: 운영자가 남긴 메모(선택).
CREATE TABLE payment_review_resolutions (
    id                    UUID         NOT NULL,
    payment_id            UUID         NOT NULL,
    refund_id             UUID,
    review_reason         VARCHAR(30)  NOT NULL,
    ledger_status         VARCHAR(20)  NOT NULL,
    ledger_transaction_id UUID,
    to_status             VARCHAR(20)  NOT NULL,
    actor_id              UUID         NOT NULL,
    description           VARCHAR(255) NOT NULL,
    memo                  VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_payment_review_resolutions PRIMARY KEY (id),
    CONSTRAINT fk_payment_review_resolutions_payment_id FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_payment_review_resolutions_refund_id FOREIGN KEY (refund_id) REFERENCES refunds (id),
    CONSTRAINT ck_payment_review_resolutions_review_reason
        CHECK (review_reason IN ('LEDGER_REQUEST_NOT_ACCEPTED', 'RETRY_EXHAUSTED')),
    CONSTRAINT ck_payment_review_resolutions_ledger_status
        CHECK (ledger_status IN ('POSTED', 'REJECTED', 'NOT_FOUND')),
    CONSTRAINT ck_payment_review_resolutions_ledger_transaction_id
        CHECK ((ledger_status = 'NOT_FOUND') = (ledger_transaction_id IS NULL)),
    CONSTRAINT ck_payment_review_resolutions_to_status
        CHECK (to_status IN ('APPROVED', 'DECLINED', 'COMPLETED', 'REQUESTED'))
);

-- 결제별 처리 기록을 시간순으로 조회합니다.
CREATE INDEX ix_payment_review_resolutions_payment_id_created_at
    ON payment_review_resolutions (payment_id, created_at);

-- 결제 목록 조회(가맹점별·사용자별) 인덱스입니다. 목록은 created_at desc, id desc 순으로 정렬하고 페이지마다 count를 함께 조회합니다.
-- 가맹점: merchant_id 인덱스가 없어 전체를 읽고(Seq Scan) 정렬했습니다.
-- 사용자: UNIQUE(user_id, idempotency_key)로 대상은 찾지만 정렬은 따로 했습니다.
-- 측정(2026-11-04, 결제 100만 건 중 한 가맹점 20만 건): 가맹점 목록 73.7ms → 0.09ms, count 67.2ms → 24.4ms.
--   다른 가맹점 결제가 늘어도(10만~300만 건) 인덱스가 있으면 작은 가맹점 목록 API는 약 22ms로 일정했습니다.
CREATE INDEX ix_payments_merchant_id_created_at_id ON payments (merchant_id, created_at DESC, id DESC);
CREATE INDEX ix_payments_user_id_created_at_id ON payments (user_id, created_at DESC, id DESC);
