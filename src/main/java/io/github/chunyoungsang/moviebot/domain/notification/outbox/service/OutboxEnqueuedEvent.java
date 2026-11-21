package io.github.chunyoungsang.moviebot.domain.notification.outbox.service;

/**
 * Outbox 에 보낼 알림이 기록됨. OutboxService.enqueue 가 발행한다.
 * 커밋 이후 ImmediateDispatchListener 가 받아 즉시 발송을 시도한다
 * (롤백되면 이벤트도 사라지고, 리스너가 실행되지 못해도 OutboxRelayScheduler 가 나중에 발송한다).
 */
public record OutboxEnqueuedEvent(Long outboxId) {
}
