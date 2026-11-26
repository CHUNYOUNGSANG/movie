package io.github.chunyoungsang.moviebot.domain.notification.outbox.dispatch;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 리스너가 발송하지 못한 Outbox(서버 재시작, 스레드풀 거부 등)와 재시도 대상을 주기적으로 발송한다.
 *
 * - 발송할 건이 한 묶음(relayBatchSize)보다 많으면 다음 실행을 기다리지 않고 이어서 보낸다 (최대 relayMaxRunTime).
 * - 한 묶음은 relay 전용 스레드풀(outboxRelayExecutor)로 나눠 보낸다. 전체 속도는 텔레그램 속도 제한기가 지킨다.
 *   예전에는 "10초마다 50건을 한 스레드에서 차례로" 보내 초당 약 3.3건으로 묶였다 (부하 테스트에서 발견, loadtest/).
 *
 * ShedLock 을 걸지 않는다: 서버가 여러 대여도 한 건은 조건부 UPDATE 선점(claim)에 성공한 한 대만 보낸다.
 * 락을 걸면 오히려 한 대만 발송하게 되어 처리량이 줄고, 그 서버가 느리면 전체 발송이 밀린다.
 */
@Slf4j
@Component
public class OutboxRelayScheduler {

    private final OutboxService outboxService;
    private final NotificationDispatcher dispatcher;
    private final OutboxProperties properties;
    private final Executor relayExecutor;

    public OutboxRelayScheduler(OutboxService outboxService, NotificationDispatcher dispatcher,
                                OutboxProperties properties,
                                @Qualifier("outboxRelayExecutor") Executor relayExecutor) {
        this.outboxService = outboxService;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.relayExecutor = relayExecutor;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.relay-delay-ms:10000}")
    public void relay() {
        int released = outboxService.releaseStaleLocks();
        if (released > 0) {
            log.warn("발송 중 멈춘 Outbox {}건을 재시도 대상으로 되돌림", released);
        }

        long deadline = System.nanoTime() + properties.relayMaxRunTime().toNanos();
        int sent = 0;
        while (System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted()) {
            List<Long> due = outboxService.findDueIds(properties.relayBatchSize());
            if (due.isEmpty()) break;

            CompletableFuture.allOf(due.stream()
                    .map(id -> CompletableFuture.runAsync(() -> dispatcher.dispatch(id), relayExecutor))
                    .toArray(CompletableFuture[]::new)).join();   // 묶음이 다 끝나야 다음 묶음을 조회 (같은 건을 두 번 꺼내지 않게)
            sent += due.size();
            if (due.size() < properties.relayBatchSize()) break;   // 남은 건 없음
        }
        if (sent > 0) {
            log.info("relay 발송 {}건", sent);
        }
    }
}
