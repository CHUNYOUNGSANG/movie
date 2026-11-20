package domain.notification.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 리스너가 발송하지 못한 Outbox(서버 재시작, 스레드풀 거부 등)와 재시도 대상을 주기적으로 발송한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private static final int BATCH_SIZE = 50;

    private final NotificationOutboxService outboxService;
    private final NotificationDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${notification.outbox.relay-delay-ms:10000}")
    public void relay() {
        int released = outboxService.releaseStaleLocks();
        if (released > 0) {
            log.warn("발송 중 멈춘 Outbox {}건을 재시도 대상으로 되돌림", released);
        }
        for (Long outboxId : outboxService.findDueIds(BATCH_SIZE)) {
            dispatcher.dispatch(outboxId);
        }
    }
}
