package domain.notification.event;

import domain.telegram.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 매칭 처리 트랜잭션이 커밋된 뒤에만 알림을 발송한다.
 * 롤백되면 이벤트가 버려지므로, DB 에는 미발송인데 사용자에게는 이미 발송된 상태가 생기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSendListener {

    private final MessageSender messageSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatched(NotificationMatchedEvent event) {
        messageSender.sendAlerts(event.chatId(), event.results());
        log.info("알림 발송 요청: subscriptionId={}, chatId={}, {}개 시간대",
                event.subscriptionId(), event.chatId(), event.results().size());
    }
}
