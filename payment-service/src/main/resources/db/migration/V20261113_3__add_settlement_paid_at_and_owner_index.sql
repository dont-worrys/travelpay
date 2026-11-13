-- 정산 입금 시각: 결제 서비스가 PAID를 확정한 시각(정산 요청·복구 스케줄러·운영자·자동 처리 모두). PAID가 아니면 비어 있습니다.
-- 기존 PAID 행은 정확한 시각이 남아 있지 않아 마지막 변경 시각(updated_at)으로 채웁니다.
ALTER TABLE settlements ADD COLUMN paid_at TIMESTAMPTZ;
UPDATE settlements SET paid_at = updated_at WHERE status = 'PAID';
ALTER TABLE settlements
    ADD CONSTRAINT ck_settlements_paid_at CHECK ((status = 'PAID') = (paid_at IS NOT NULL));

-- 내 정산 목록(GET /settlements/me): 정산금을 받은 주인 기준 최신순
CREATE INDEX ix_settlements_owner_user_id_created_at_id ON settlements (owner_user_id, created_at DESC, id DESC);
