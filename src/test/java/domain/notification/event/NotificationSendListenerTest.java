package domain.notification.event;

import domain.notification.service.NotificationStateService;
import domain.telegram.service.MessageSendException;
import domain.telegram.service.MessageSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationSendListener")
class NotificationSendListenerTest {

    @Mock private MessageSender messageSender;
    @Mock private NotificationStateService notificationStateService;
    @InjectMocks private NotificationSendListener listener;

    private final NotificationMatchedEvent event = new NotificationMatchedEvent(1L, "123", List.of());

    @Test
    @DisplayName("발송에 성공하면 성공을 기록한다")
    void recordsSuccess() {
        listener.onMatched(event);

        then(notificationStateService).should().recordSuccess(1L);
        then(notificationStateService).should(never()).recordFailure(anyLong());
    }

    @Test
    @DisplayName("발송에 실패하면 실패를 기록한다 (예외를 밖으로 던지지 않음)")
    void recordsFailure() {
        willThrow(new MessageSendException("장애", null)).given(messageSender).sendAlerts(anyString(), any());

        listener.onMatched(event);

        then(notificationStateService).should().recordFailure(1L);
        then(notificationStateService).should(never()).recordSuccess(anyLong());
    }
}
