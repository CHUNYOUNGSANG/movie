package io.github.chunyoungsang.moviebot.domain.notification.outbox.entity;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NotificationOutbox 상태 전이")
class NotificationOutboxTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 24, 10, 0);

    @Test
    @DisplayName("create: 구독자의 chatId 로, 바로 발송 가능한 PENDING")
    void create() {
        NotificationSubscription sub = NotificationSubscription.builder()
                .user(User.builder().chatId("111").build()).build();

        NotificationOutbox outbox = NotificationOutbox.create(sub, "본문", NOW);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getChatId()).isEqualTo("111");
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(outbox.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("markSent: SENT, 시도 횟수 +1, 선점 해제")
    void markSent() {
        NotificationOutbox outbox = sending();

        outbox.markSent();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(outbox.getAttemptCount()).isEqualTo(2);
        assertThat(outbox.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("scheduleRetry: PENDING 으로 되돌리고 다음 시도 시각과 오류를 기록")
    void scheduleRetry() {
        NotificationOutbox outbox = sending();

        outbox.scheduleRetry(2, NOW.plusMinutes(1), "502");

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(2);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(outbox.getLastError()).isEqualTo("502");
        assertThat(outbox.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("giveUp: DEAD, 오류는 컬럼 길이(500자)에 맞춰 자른다")
    void giveUp() {
        NotificationOutbox outbox = sending();

        outbox.giveUp(3, "x".repeat(800));

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(outbox.getLastError()).hasSize(NotificationOutbox.ERROR_MAX_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(value = OutboxStatus.class, names = {"SENT", "DEAD"})
    @DisplayName("끝난 상태(SENT, DEAD)는 다시 바꿀 수 없다")
    void finishedStatesAreFinal(OutboxStatus finished) {
        NotificationOutbox outbox = NotificationOutbox.builder().outboxId(1L).status(finished).build();

        assertThatThrownBy(outbox::markSent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.scheduleRetry(1, NOW, "e")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.giveUp(1, "e")).isInstanceOf(IllegalStateException.class);
        assertThat(outbox.getStatus()).isEqualTo(finished);
    }

    private static NotificationOutbox sending() {
        return NotificationOutbox.builder().outboxId(1L).status(OutboxStatus.SENDING)
                .attemptCount(1).lockedAt(NOW).nextAttemptAt(NOW).build();
    }
}
