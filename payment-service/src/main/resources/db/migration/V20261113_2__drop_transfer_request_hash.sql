-- 같은 Idempotency-Key의 재요청이 같은 내용인지는 송금 행에 저장된 receiver_id·amount·currency·memo와
-- 직접 비교합니다. request_hash는 이 네 값의 SHA-256이라 같은 정보를 한 번 더 저장하던 컬럼이므로 지웁니다.
-- 중복 송금 방지는 그대로 uk_transfer_sender_id_idempotency_key가 맡습니다.
ALTER TABLE transfer DROP COLUMN request_hash;
