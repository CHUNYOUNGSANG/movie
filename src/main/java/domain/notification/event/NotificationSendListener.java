package domain.notification.event;

import domain.notification.service.NotificationStateService;
import domain.telegram.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 매칭 처리 트랜잭션이 커밋된 뒤에만 알림을 발송한다.
 * - 롤백되면 이벤트가 버려지므로, DB 에는 미발송인데 사용자에게는 이미 발송된 상태가 생기지 않는다.
 * - 발송은 notificationExecutor 스레드에서 비동기로 수행해 스케줄러 스레드를 막지 않는다.
 * - 발송 결과를 기록하고, 실패하면 구독을 미발송 상태로 되돌려 다음 주기에 재시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSendListener {

    private final MessageSender messageSender;
    private final NotificationStateService notificationStateService;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatched(NotificationMatchedEvent event) {
        try {
            messageSender.sendAlerts(event.chatId(), event.results());
        } catch (Exception e) {
            log.warn("알림 발송 실패, 다음 주기에 재시도: subscriptionId={}, chatId={}, cause={}",
                    event.subscriptionId(), event.chatId(), e.getMessage());
            notificationStateService.recordFailure(event.subscriptionId());
            return;
        }
        notificationStateService.recordSuccess(event.subscriptionId());
        log.info("알림 발송 완료: subscriptionId={}, chatId={}, {}개 시간대",
                event.subscriptionId(), event.chatId(), event.results().size());
    }
}
