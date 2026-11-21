package io.github.chunyoungsang.moviebot.domain.notification.outbox.service;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;
import io.github.chunyoungsang.moviebot.domain.notification.sender.MessageSendException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 발송 실패 한 번을 어떻게 처리할지 정한다 (DB 와 무관한 순수 판단).
 * - 속도 제한(429): 메시지 문제가 아니므로 시도 횟수를 쓰지 않고, 텔레그램이 알려준 시간 뒤에 다시
 * - 영구 실패(봇 차단 등)이거나 최대 시도 횟수에 도달: 포기(DEAD)
 * - 그 외(일시 장애): 지수 백오프 base × 2^(n-1), 상한 maxDelay
 */
@Component
@RequiredArgsConstructor
public class RetryPolicy {

    private final OutboxProperties properties;

    /**
     * @param attemptsSoFar 이번 실패 전까지의 시도 횟수
     * @param now           판단 기준 시각
     */
    public Decision decide(int attemptsSoFar, Exception cause, LocalDateTime now) {
        if (cause instanceof MessageSendException mse && mse.isRateLimited()) {
            return new Decision(DeliveryResult.RATE_LIMITED, attemptsSoFar, now.plus(mse.getRetryAfter()));
        }
        int attempts = attemptsSoFar + 1;
        boolean retryable = !(cause instanceof MessageSendException mse) || mse.isRetryable();
        if (!retryable || attempts >= properties.maxAttempts()) {
            return new Decision(DeliveryResult.DEAD, attempts, null);
        }
        return new Decision(DeliveryResult.RETRY, attempts, now.plus(backoff(attempts)));
    }

    /** attempts 회 실패 후 다음 시도까지의 대기 시간 */
    Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 20);   // 오버플로 방지
        Duration delay = Duration.ofMillis(properties.baseDelayMs()).multipliedBy(multiplier);
        return delay.compareTo(properties.maxDelay()) > 0 ? properties.maxDelay() : delay;
    }

    /**
     * @param result        처리 결과 (RETRY / RATE_LIMITED / DEAD)
     * @param attempts      처리 후 시도 횟수
     * @param nextAttemptAt 다음 시도 시각. DEAD 면 null
     */
    public record Decision(DeliveryResult result, int attempts, LocalDateTime nextAttemptAt) {
        public boolean isDead() {
            return result == DeliveryResult.DEAD;
        }
    }
}
