package io.github.chunyoungsang.moviebot.domain.notification.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Outbox 발송 설정 (notification.outbox.*).
 *
 * @param baseDelayMs    첫 재시도까지 대기 시간. n 번째 실패 후 base × 2^(n-1)
 * @param maxDelay       재시도 대기 시간 상한
 * @param maxAttempts    이 횟수만큼 실패하면 포기(DEAD). 속도 제한(429)은 횟수에 넣지 않는다
 * @param relayDelayMs   relay 실행 간격 (@Scheduled 가 직접 읽으므로 기본값을 그쪽에도 같게 둔다)
 * @param relayBatchSize relay 한 번에 발송할 최대 건수
 * @param staleLock      발송 중(SENDING)으로 이 시간 넘게 멈춰 있으면 서버가 죽은 것으로 보고 되돌린다
 * @param relayMaxRunTime relay 한 번이 발송을 이어 갈 수 있는 최대 시간. 넘기면 남은 건은 다음 실행에서 보낸다
 */
@ConfigurationProperties("notification.outbox")
public record OutboxProperties(
        @DefaultValue("30000") long baseDelayMs,
        @DefaultValue("1h") Duration maxDelay,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10000") long relayDelayMs,
        @DefaultValue("50") int relayBatchSize,
        @DefaultValue("5m") Duration staleLock,
        @DefaultValue("1m") Duration relayMaxRunTime
) {
    public OutboxProperties {
        if (baseDelayMs <= 0) throw new IllegalArgumentException("notification.outbox.base-delay-ms 는 1 이상이어야 합니다");
        if (maxAttempts < 1) throw new IllegalArgumentException("notification.outbox.max-attempts 는 1 이상이어야 합니다");
        if (relayBatchSize < 1) throw new IllegalArgumentException("notification.outbox.relay-batch-size 는 1 이상이어야 합니다");
        if (relayMaxRunTime == null || relayMaxRunTime.isNegative() || relayMaxRunTime.isZero())
            throw new IllegalArgumentException("notification.outbox.relay-max-run-time 은 0 보다 커야 합니다");
    }
}
