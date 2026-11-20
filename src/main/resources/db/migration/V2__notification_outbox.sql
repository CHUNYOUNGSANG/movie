-- 알림 발송 Outbox
-- 구독 매칭(triggered=true)과 같은 트랜잭션에서 "보낼 메시지"를 기록하고,
-- 커밋 이후 리스너 또는 relay 스케줄러가 꺼내서 발송한다.
CREATE TABLE notification_outbox (
    outbox_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    subscription_id BIGINT       NOT NULL,
    chat_id         VARCHAR(255) NOT NULL,
    message         VARCHAR(4096) NOT NULL,       -- 텔레그램 메시지 최대 길이
    status          VARCHAR(20)  NOT NULL,        -- PENDING / SENDING / SENT / DEAD
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    locked_at       DATETIME(6),
    last_error      VARCHAR(500),
    created_at      DATETIME(6),
    updated_at      DATETIME(6),
    CONSTRAINT fk_outbox_subscription FOREIGN KEY (subscription_id) REFERENCES notification_subscriptions (subscription_id)
);

-- relay 가 "발송할 차례인 PENDING" 을 찾는 조회용
CREATE INDEX idx_outbox_status_next_attempt ON notification_outbox (status, next_attempt_at);
