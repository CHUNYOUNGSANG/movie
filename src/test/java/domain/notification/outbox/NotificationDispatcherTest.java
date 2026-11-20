package domain.notification.outbox;

import domain.notification.outbox.NotificationOutboxService.OutboxMessage;
import domain.telegram.service.MessageSendException;
import domain.telegram.service.MessageSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDispatcher")
class NotificationDispatcherTest {

    @Mock private NotificationOutboxService outboxService;
    @Mock private MessageSender messageSender;
    @InjectMocks private NotificationDispatcher dispatcher;

    @Test
    @DisplayName("선점에 실패하면(다른 쪽이 처리 중) 발송하지 않는다")
    void skipsWhenNotClaimed() {
        given(outboxService.claim(1L)).willReturn(Optional.empty());

        dispatcher.dispatch(1L);

        then(messageSender).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("발송에 성공하면 SENT 로 기록한다")
    void marksSent() {
        given(outboxService.claim(1L)).willReturn(Optional.of(new OutboxMessage(1L, "123", "본문")));

        dispatcher.dispatch(1L);

        then(messageSender).should().deliver("123", "본문");
        then(outboxService).should().markSent(1L);
        then(outboxService).should(never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("발송에 실패하면 실패를 기록한다 (예외를 밖으로 던지지 않음)")
    void marksFailed() {
        given(outboxService.claim(1L)).willReturn(Optional.of(new OutboxMessage(1L, "123", "본문")));
        MessageSendException error = new MessageSendException("장애", null);
        willThrow(error).given(messageSender).deliver(anyString(), anyString());

        dispatcher.dispatch(1L);

        then(outboxService).should().markFailed(eq(1L), eq(error));
        then(outboxService).should(never()).markSent(anyLong());
    }
}
