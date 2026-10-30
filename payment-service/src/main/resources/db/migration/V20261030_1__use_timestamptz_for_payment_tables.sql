-- 결제 관련 시각을 다른 서비스와 같이 시간대 정보가 있는 TIMESTAMPTZ로 통일합니다.
-- 기존 값은 서버가 KST(Asia/Seoul) 기준 LocalDateTime으로 저장했으므로 KST로 해석해 변환합니다.
ALTER TABLE merchants
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE payments
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE payment_histories
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE refunds
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Seoul';
