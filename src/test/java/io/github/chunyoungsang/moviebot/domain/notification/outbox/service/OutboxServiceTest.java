package io.github.chunyoungsang.moviebot.domain.notification.outbox.service;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.NotificationOutbox;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.OutboxStatus;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.repository.NotificationOutboxRepository;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.domain.notification.sender.MessageSendException;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxService")
class OutboxServiceTest {

    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private NotificationHistoryRepository historyRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    private OutboxService service;

    private NotificationOutbox outbox;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties(30_000, Duration.ofHours(1), 3, 10_000, 50, Duration.ofMinutes(5));
        service = new OutboxService(outboxRepository, historyRepository, properties, new RetryPolicy(properties), eventPublisher);
        outbox = sendingOutbox(0);
        lenient().when(outboxRepository.findById(1L)).thenAnswer(inv -> Optional.of(outbox));
    }

    /** 리스너/relay 가 선점해 발송 중인 Outbox */
    private static NotificationOutbox sendingOutbox(int attemptCount) {
        return NotificationOutbox.builder()
                .outboxId(1L)
                .subscription(NotificationSubscription.builder().subscriptionId(10L).build())
                .chatId("123").message("본문")
                .status(OutboxStatus.SENDING)
                .attemptCount(attemptCount)
                .nextAttemptAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("enqueue: PENDING 으로 저장하고, 저장된 id 로 즉시 발송 이벤트를 발행한다")
    void enqueueSavesAndPublishes() {
        given(outboxRepository.save(any())).willAnswer(inv -> {
            NotificationOutbox saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "outboxId", 77L);   // DB 가 매기는 id 흉내
            return saved;
        });
        NotificationSubscription sub = NotificationSubscription.builder()
                .subscriptionId(10L).user(User.builder().chatId("123").build()).build();

        service.enqueue(sub, "본문");

        then(outboxRepository).should().save(argThat(o -> o.getStatus() == OutboxStatus.PENDING && "123".equals(o.getChatId())));
        then(eventPublisher).should().publishEvent(new OutboxEnqueuedEvent(77L));
    }

    @Test
    @DisplayName("일시 장애면 시도 횟수를 늘리고 백오프 후 다시 PENDING")
    void retryableFailureGoesBackToPending() {
        LocalDateTime before = LocalDateTime.now();

        DeliveryResult result = service.markFailed(1L, new MessageSendException("502", null, true));

        assertThat(result).isEqualTo(DeliveryResult.RETRY);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(30));
        then(historyRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("최대 시도 횟수에 도달하면 DEAD + FAIL 이력")
    void exhaustedRetriesGoToDead() {
        outbox = sendingOutbox(2);   // 이번이 3번째 시도

        DeliveryResult result = service.markFailed(1L, new MessageSendException("502", null, true));

        assertThat(result).isEqualTo(DeliveryResult.DEAD);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(outbox.getAttemptCount()).isEqualTo(3);
        then(historyRepository).should().save(argThat(h -> "FAIL".equals(h.getStatus())));
    }

    @Test
    @DisplayName("영구 실패(재시도 불가)면 첫 시도라도 바로 DEAD")
    void permanentFailureGoesToDeadImmediately() {
        service.markFailed(1L, new MessageSendException("403 bot was blocked", null, false));

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("속도 제한(429)이면 시도 횟수를 쓰지 않고 retry_after 뒤로 미룬다 (최대 횟수 직전이어도 DEAD 아님)")
    void rateLimitedDoesNotConsumeAttempt() {
        outbox = sendingOutbox(2);   // 일반 실패였다면 이번에 DEAD
        LocalDateTime before = LocalDateTime.now();

        DeliveryResult result = service.markFailed(1L, new MessageSendException("429", null, true, Duration.ofSeconds(5)));
        assertThat(result).isEqualTo(DeliveryResult.RATE_LIMITED);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(2);
        assertThat(outbox.getNextAttemptAt())
                .isAfterOrEqualTo(before.plusSeconds(5))
                .isBefore(before.plusSeconds(30));   // 지수 백오프(30초~)가 아니라 retry_after 기준
        then(historyRepository).should(never()).save(any());
    }
}
