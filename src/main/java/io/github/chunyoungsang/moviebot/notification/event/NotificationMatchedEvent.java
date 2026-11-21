package io.github.chunyoungsang.moviebot.notification.event;

/**
 * 구독 매칭으로 Outbox 에 발송 대상이 기록된 시점에 발행되는 이벤트.
 * 커밋 이후 NotificationSendListener 가 받아 즉시 발송을 시도한다.
 * (리스너가 실행되지 못해도 OutboxRelayScheduler 가 나중에 발송한다)
 */
public record NotificationMatchedEvent(Long outboxId) {
}
