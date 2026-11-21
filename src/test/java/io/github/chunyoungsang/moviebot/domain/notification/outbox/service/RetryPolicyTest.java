package io.github.chunyoungsang.moviebot.domain.notification.outbox.service;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;
import io.github.chunyoungsang.moviebot.domain.notification.sender.MessageSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** DB·mock 없이 재시도 판단만 검증 */
@DisplayName("RetryPolicy")
class RetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 24, 10, 0);
    private final RetryPolicy policy = new RetryPolicy(
            new OutboxProperties(30_000, Duration.ofHours(1), 3, 10_000, 50, Duration.ofMinutes(5)));

    @Test
    @DisplayName("일시 장애: 시도 횟수 +1, 백오프 뒤 재시도")
    void retryable() {
        RetryPolicy.Decision d = policy.decide(0, new MessageSendException("502", null, true), NOW);

        assertThat(d.result()).isEqualTo(DeliveryResult.RETRY);
        assertThat(d.attempts()).isEqualTo(1);
        assertThat(d.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("MessageSendException 이 아닌 예외(예상 못한 오류)도 일시 장애로 보고 재시도")
    void unknownExceptionIsRetried() {
        assertThat(policy.decide(0, new IllegalStateException("?"), NOW).result()).isEqualTo(DeliveryResult.RETRY);
    }

    @Test
    @DisplayName("최대 시도 횟수에 도달하면 포기")
    void exhausted() {
        RetryPolicy.Decision d = policy.decide(2, new MessageSendException("502", null, true), NOW);

        assertThat(d.result()).isEqualTo(DeliveryResult.DEAD);
        assertThat(d.attempts()).isEqualTo(3);
        assertThat(d.nextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("영구 실패(봇 차단 등)는 첫 시도라도 포기")
    void permanent() {
        assertThat(policy.decide(0, new MessageSendException("403", null, false), NOW).isDead()).isTrue();
    }

    @Test
    @DisplayName("속도 제한(429)은 시도 횟수를 쓰지 않고 retry_after 뒤로 (최대 횟수 직전이어도 포기하지 않음)")
    void rateLimited() {
        RetryPolicy.Decision d = policy.decide(2,
                new MessageSendException("429", null, true, Duration.ofSeconds(5)), NOW);

        assertThat(d.result()).isEqualTo(DeliveryResult.RATE_LIMITED);
        assertThat(d.attempts()).isEqualTo(2);
        assertThat(d.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("백오프는 base × 2^(n-1) 로 늘어나고 상한(1시간)을 넘지 않는다")
    void backoffGrowsExponentiallyWithCap() {
        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.backoff(30)).isEqualTo(Duration.ofHours(1));
    }
}
