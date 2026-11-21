package io.github.chunyoungsang.moviebot.notification.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 적체 상태를 지표로 내보낸다.
 * - notification.outbox.messages{status}: 상태별 건수 (PENDING / SENDING / DEAD)
 * - notification.outbox.oldest.pending.age: 가장 오래 기다리고 있는 PENDING 의 대기 시간(초)
 *
 * 지표를 읽을 때마다 DB 를 조회하지 않고, 주기적으로 한 번 집계해 둔 값을 돌려준다
 * (수집 주기·수집기 수와 무관하게 DB 부하가 일정).
 * DB 전체 기준 값이므로 서버가 여러 대면 모두 같은 값을 보고한다 (합계가 아니라 max 로 집계할 것).
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final NotificationOutboxRepository outboxRepository;
    private final Map<OutboxStatus, AtomicLong> counts = new EnumMap<>(OutboxStatus.class);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public OutboxMetrics(NotificationOutboxRepository outboxRepository, MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        for (OutboxStatus status : new OutboxStatus[]{OutboxStatus.PENDING, OutboxStatus.SENDING, OutboxStatus.DEAD}) {
            AtomicLong value = new AtomicLong();
            counts.put(status, value);
            Gauge.builder("notification.outbox.messages", value, AtomicLong::get)
                    .description("발송되지 않은 알림 수 (상태별)")
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("notification.outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::get)
                .description("가장 오래 발송을 기다리고 있는 알림의 대기 시간")
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${monitoring.outbox.refresh-ms:30000}")
    public void refresh() {
        try {
            Map<OutboxStatus, Long> latest = new EnumMap<>(OutboxStatus.class);
            outboxRepository.countUnsentByStatus().forEach(v -> latest.put(v.getStatus(), v.getCount()));
            counts.forEach((status, value) -> value.set(latest.getOrDefault(status, 0L)));

            LocalDateTime oldest = outboxRepository.findOldestPendingCreatedAt();
            oldestPendingAgeSeconds.set(oldest == null ? 0
                    : Math.max(0, Duration.between(oldest, LocalDateTime.now()).toSeconds()));
        } catch (Exception e) {
            // 지표 집계 실패가 서비스에 영향을 주면 안 된다 (이전 값 유지)
            log.warn("Outbox 지표 집계 실패: {}", e.getMessage());
        }
    }
}
