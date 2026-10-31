-- 접수(REQUESTED) 상태 송금의 취소(CANCELLED)를 허용합니다.
-- 1차 MVP는 원장 연동 전이라 자금 이동이 없으므로, 취소 시 복구할 금액이 없습니다.
ALTER TABLE transfer DROP CONSTRAINT ck_transfer_status;
ALTER TABLE transfer ADD CONSTRAINT ck_transfer_status CHECK (status IN ('REQUESTED', 'CANCELLED'));
