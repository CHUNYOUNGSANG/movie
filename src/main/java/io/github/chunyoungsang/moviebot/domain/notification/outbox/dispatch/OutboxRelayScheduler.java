package io.github.chunyoungsang.moviebot.domain.notification.outbox.dispatch;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 리스너가 발송하지 못한 Outbox(서버 재시작, 스레드풀 거부 등)와 재시도 대상을 주기적으로 발송한다.
 *
 * ShedLock 을 걸지 않는다: 서버가 여러 대여도 한 건은 조건부 UPDATE 선점(claim)에 성공한 한 대만 보낸다.
 * 락을 걸면 오히려 한 대만 발송하게 되어 처리량이 줄고, 그 서버가 느리면 전체 발송이 밀린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private final OutboxService outboxService;
    private final NotificationDispatcher dispatcher;
    private final OutboxProperties properties;

    @Scheduled(fixedDelayString = "${notification.outbox.relay-delay-ms:10000}")
    public void relay() {
        int released = outboxService.releaseStaleLocks();
        if (released > 0) {
            log.warn("발송 중 멈춘 Outbox {}건을 재시도 대상으로 되돌림", released);
        }
        for (Long outboxId : outboxService.findDueIds(properties.relayBatchSize())) {
            dispatcher.dispatch(outboxId);
        }
    }
}
