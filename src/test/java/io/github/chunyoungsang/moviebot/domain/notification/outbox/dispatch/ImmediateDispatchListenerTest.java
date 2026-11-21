package io.github.chunyoungsang.moviebot.domain.notification.outbox.dispatch;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.dispatch.NotificationDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImmediateDispatchListener")
class ImmediateDispatchListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private ImmediateDispatchListener listener;

    @Test
    @DisplayName("커밋 이벤트를 받으면 해당 Outbox 발송을 위임한다")
    void dispatchesOutbox() {
        listener.onMatched(new OutboxEnqueuedEvent(10L));

        then(dispatcher).should().dispatch(10L);
    }
}
