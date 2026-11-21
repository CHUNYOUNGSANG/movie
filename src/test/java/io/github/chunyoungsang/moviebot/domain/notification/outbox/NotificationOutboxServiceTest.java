package io.github.chunyoungsang.moviebot.domain.notification.outbox;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.domain.notification.sender.MessageSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationOutboxService.markFailed()")
class NotificationOutboxServiceTest {

    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private NotificationHistoryRepository historyRepository;
    @InjectMocks private NotificationOutboxService service;

    private NotificationOutbox outbox;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseDelayMs", 30_000L);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        outbox = NotificationOutbox.builder()
                .outboxId(1L)
                .subscription(NotificationSubscription.builder().subscriptionId(10L).build())
                .chatId("123").message("본문")
                .status(OutboxStatus.SENDING)
                .nextAttemptAt(LocalDateTime.now())
                .build();
        lenient().when(outboxRepository.findById(1L)).thenReturn(Optional.of(outbox));
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
        outbox.setAttemptCount(2);   // 이번이 3번째 시도

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
        outbox.setAttemptCount(2);   // 일반 실패였다면 이번에 DEAD
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

    @Test
    @DisplayName("백오프는 base × 2^(n-1) 로 늘어나고 1시간을 넘지 않는다")
    void backoffGrowsExponentiallyWithCap() {
        assertThat(service.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(service.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(service.backoff(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(service.backoff(30)).isEqualTo(Duration.ofHours(1));
    }
}
