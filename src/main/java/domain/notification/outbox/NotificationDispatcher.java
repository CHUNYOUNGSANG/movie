package domain.notification.outbox;

import domain.telegram.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbox 한 건을 발송한다. 선점(claim) → 발송(트랜잭션 밖) → 결과 기록 순서.
 * AFTER_COMMIT 리스너(즉시 발송)와 relay 스케줄러(누락분/재시도) 양쪽에서 호출된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationOutboxService outboxService;
    private final MessageSender messageSender;

    public void dispatch(Long outboxId) {
        NotificationOutboxService.OutboxMessage msg = outboxService.claim(outboxId).orElse(null);
        if (msg == null) return;   // 다른 쪽에서 이미 처리 중이거나 아직 차례가 아님

        try {
            messageSender.deliver(msg.chatId(), msg.message());
        } catch (Exception e) {
            log.warn("알림 발송 실패: outboxId={}, chatId={}, cause={}", outboxId, msg.chatId(), e.getMessage());
            outboxService.markFailed(outboxId, e);
            return;
        }
        outboxService.markSent(outboxId);
        log.info("알림 발송 완료: outboxId={}, chatId={}", outboxId, msg.chatId());
    }
}
