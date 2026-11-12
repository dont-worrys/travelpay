-- 송금 MANUAL_REVIEW 자동 대사: 원장 결과가 확정적인 송금(POSTED 항목 일치, REJECTED)을 스케줄러가 확정하고
-- 운영자 확정과 같은 이력 테이블에 SYSTEM으로 남깁니다. SYSTEM 이력은 변경한 회원이 없어 actor_id가 비어 있습니다.
-- 기존 행은 모두 OPERATOR·actor_id 있음이라 새 제약을 그대로 만족합니다.
ALTER TABLE transfer_status_history ALTER COLUMN actor_id DROP NOT NULL;

ALTER TABLE transfer_status_history DROP CONSTRAINT ck_transfer_status_history_actor_type;
ALTER TABLE transfer_status_history
    ADD CONSTRAINT ck_transfer_status_history_actor_type CHECK (actor_type IN ('OPERATOR', 'SYSTEM'));

-- 운영자 이력은 운영자 ID가 반드시 있고, SYSTEM 이력은 없습니다.
ALTER TABLE transfer_status_history
    ADD CONSTRAINT ck_transfer_status_history_actor_id CHECK ((actor_type = 'OPERATOR') = (actor_id IS NOT NULL));
