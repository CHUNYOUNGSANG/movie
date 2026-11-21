package io.github.chunyoungsang.moviebot.notification.event;

import io.github.chunyoungsang.moviebot.notification.outbox.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 매칭 트랜잭션이 커밋된 뒤 Outbox 를 즉시 발송한다 (지연 최소화 경로).
 * - 롤백되면 이벤트와 Outbox 가 함께 사라지므로 발송되지 않는다.
 * - 이 리스너가 실행되지 못해도(서버 재시작, 스레드풀 거부) Outbox 에 남아 있으므로 relay 가 발송한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationSendListener {

    private final NotificationDispatcher dispatcher;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatched(NotificationMatchedEvent event) {
        dispatcher.dispatch(event.outboxId());
    }
}
