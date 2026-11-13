-- MANUAL_REVIEW 송금 조회용 부분 인덱스입니다.
-- 복구 스케줄러가 10초마다 세는 MANUAL_REVIEW 건수(transfer.manual_review 지표)와 약 1분마다 도는 자동 대사 대상 조회
-- (updated_at, id 순 커서)가 인덱스 없이 테이블 전체를 읽고 있었습니다. MANUAL_REVIEW 행은 소수라 인덱스 크기는 작습니다.
CREATE INDEX ix_transfer_manual_review_updated_at ON transfer (updated_at, id) WHERE status = 'MANUAL_REVIEW';
