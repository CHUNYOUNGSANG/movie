package io.github.chunyoungsang.moviebot.domain.notification.event;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.NotificationDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationSendListener")
class NotificationSendListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private NotificationSendListener listener;

    @Test
    @DisplayName("커밋 이벤트를 받으면 해당 Outbox 발송을 위임한다")
    void dispatchesOutbox() {
        listener.onMatched(new NotificationMatchedEvent(10L));

        then(dispatcher).should().dispatch(10L);
    }
}
