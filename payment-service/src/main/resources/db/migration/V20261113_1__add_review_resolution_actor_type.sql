-- MANUAL_REVIEW 자동 처리: 원장에 결과가 확정된 결제·환불·정산(POSTED 항목 일치, REJECTED)을 스케줄러가 처리하고
-- 운영자 처리와 같은 처리 기록 테이블에 SYSTEM으로 남깁니다. SYSTEM 기록은 처리한 회원이 없어 actor_id가 비어 있습니다.
-- 기존 행은 모두 운영자 처리라 OPERATOR로 채웁니다.
ALTER TABLE payment_review_resolutions ADD COLUMN actor_type VARCHAR(20);
UPDATE payment_review_resolutions SET actor_type = 'OPERATOR';
ALTER TABLE payment_review_resolutions ALTER COLUMN actor_type SET NOT NULL;
ALTER TABLE payment_review_resolutions ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE payment_review_resolutions
    ADD CONSTRAINT ck_payment_review_resolutions_actor_type CHECK (actor_type IN ('OPERATOR', 'SYSTEM'));
-- 운영자 처리는 운영자 ID가 반드시 있고, 자동 처리는 없습니다.
ALTER TABLE payment_review_resolutions
    ADD CONSTRAINT ck_payment_review_resolutions_actor_id CHECK ((actor_type = 'OPERATOR') = (actor_id IS NOT NULL));

ALTER TABLE settlement_review_resolutions ADD COLUMN actor_type VARCHAR(20);
UPDATE settlement_review_resolutions SET actor_type = 'OPERATOR';
ALTER TABLE settlement_review_resolutions ALTER COLUMN actor_type SET NOT NULL;
ALTER TABLE settlement_review_resolutions ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE settlement_review_resolutions
    ADD CONSTRAINT ck_settlement_review_resolutions_actor_type CHECK (actor_type IN ('OPERATOR', 'SYSTEM'));
ALTER TABLE settlement_review_resolutions
    ADD CONSTRAINT ck_settlement_review_resolutions_actor_id CHECK ((actor_type = 'OPERATOR') = (actor_id IS NOT NULL));
