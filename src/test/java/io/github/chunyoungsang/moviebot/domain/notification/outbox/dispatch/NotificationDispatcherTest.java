package io.github.chunyoungsang.moviebot.domain.notification.outbox.dispatch;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.DeliveryResult;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService.OutboxMessage;
import io.github.chunyoungsang.moviebot.domain.notification.sender.MessageSendException;
import io.github.chunyoungsang.moviebot.domain.notification.sender.MessageSender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock private OutboxService outboxService;
    @Mock private MessageSender messageSender;
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();
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
        given(outboxService.claim(1L)).willReturn(Optional.of(new OutboxMessage(1L, "123", "본문", true)));

        dispatcher.dispatch(1L);

        then(messageSender).should().deliver("123", "본문");
        then(outboxService).should().markSent(1L);
        then(outboxService).should(never()).markFailed(anyLong(), any());
        assertThat(deliveries("sent")).isEqualTo(1);
    }

    @Test
    @DisplayName("발송에 실패하면 실패를 기록한다 (예외를 밖으로 던지지 않음)")
    void marksFailed() {
        given(outboxService.claim(1L)).willReturn(Optional.of(new OutboxMessage(1L, "123", "본문", true)));
        MessageSendException error = new MessageSendException("장애", null);
        willThrow(error).given(messageSender).deliver(anyString(), anyString());
        given(outboxService.markFailed(1L, error)).willReturn(DeliveryResult.DEAD);

        dispatcher.dispatch(1L);

        then(outboxService).should().markFailed(eq(1L), eq(error));
        then(outboxService).should(never()).markSent(anyLong());
        assertThat(deliveries("dead")).as("실패 처리 결과가 지표 태그로 기록됨").isEqualTo(1);
        assertThat(meterRegistry.find("notification.delivery").tag("result", "sent").timer()).isNull();
    }

    @Test
    @DisplayName("선점했을 때 받는 사람이 구독을 해제했으면 보내지 않고 CANCELED 로 끝낸다 (실패로 기록하지 않음)")
    void cancelsWhenRecipientInactive() {
        given(outboxService.claim(1L)).willReturn(Optional.of(new OutboxMessage(1L, "123", "본문", false)));

        dispatcher.dispatch(1L);

        then(messageSender).shouldHaveNoInteractions();
        then(outboxService).should().cancel(eq(1L), anyString());
        then(outboxService).should(never()).markFailed(anyLong(), any());
        assertThat(deliveries("canceled")).isEqualTo(1);
    }

    private long deliveries(String result) {
        return meterRegistry.get("notification.delivery").tag("result", result).timer().count();
    }
}
