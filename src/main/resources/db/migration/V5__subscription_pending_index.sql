-- 크롤링 대상(미발송 + 상영일 이후)·만료 대상(미발송 + 상영일 지남) 조회용.
-- 매칭됐거나 지난 신청이 쌓여도 대기 중인 신청만 범위로 읽는다.
CREATE INDEX idx_subscriptions_pending ON notification_subscriptions (is_triggered, target_date);

-- users.active 에 통계가 없으면 옵티마이저가 "active = TRUE" 를 10% 로 추정해
-- 사용자 전체를 먼저 훑고 사용자별 신청을 전부 읽는 계획을 고른다 (실측 23.5ms vs 4.8ms, 신청 5만 건).
-- 인덱스가 있으면 실행 시점에 실제 행 수를 세어(index dive) 신청 인덱스부터 읽는다.
CREATE INDEX idx_users_active ON users (active);
