-- 상영일이 지나도록 예매가 열리지 않은 신청을 만료 처리한 시각 (NULL = 만료 안 됨)
-- 만료 안내를 한 번만 보내고, /list 의 "대기 중" 목록에서 빼기 위해 쓴다
ALTER TABLE notification_subscriptions ADD COLUMN expired_at DATETIME(6) NULL;
