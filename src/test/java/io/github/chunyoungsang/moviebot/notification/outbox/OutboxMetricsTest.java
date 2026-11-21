package io.github.chunyoungsang.moviebot.notification.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("OutboxMetrics")
class OutboxMetricsTest {

    private final NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(repository, registry);

    @Test
    @DisplayName("상태별 건수와 가장 오래된 PENDING 의 대기 시간을 집계한다")
    void refreshesCountsAndOldestPendingAge() {
        given(repository.countUnsentByStatus()).willReturn(List.of(view(OutboxStatus.PENDING, 4), view(OutboxStatus.DEAD, 2)));
        given(repository.findOldestPendingCreatedAt()).willReturn(LocalDateTime.now().minusMinutes(10));

        metrics.refresh();

        assertThat(gauge("PENDING")).isEqualTo(4);
        assertThat(gauge("SENDING")).as("조회 결과에 없는 상태는 0").isZero();
        assertThat(gauge("DEAD")).isEqualTo(2);
        assertThat(registry.get("notification.outbox.oldest.pending.age").gauge().value()).isBetween(599.0, 601.0);
    }

    @Test
    @DisplayName("건수가 줄면 0 으로 돌아가고, PENDING 이 없으면 대기 시간은 0")
    void resetsWhenDrained() {
        given(repository.countUnsentByStatus()).willReturn(List.of(view(OutboxStatus.PENDING, 4)));
        given(repository.findOldestPendingCreatedAt()).willReturn(LocalDateTime.now().minusMinutes(10));
        metrics.refresh();

        given(repository.countUnsentByStatus()).willReturn(List.of());
        given(repository.findOldestPendingCreatedAt()).willReturn(null);
        metrics.refresh();

        assertThat(gauge("PENDING")).isZero();
        assertThat(registry.get("notification.outbox.oldest.pending.age").gauge().value()).isZero();
    }

    @Test
    @DisplayName("집계 쿼리가 실패해도 예외를 던지지 않고 이전 값을 유지한다")
    void keepsPreviousValuesOnFailure() {
        given(repository.countUnsentByStatus()).willReturn(List.of(view(OutboxStatus.DEAD, 2)));
        metrics.refresh();

        given(repository.countUnsentByStatus()).willThrow(new IllegalStateException("DB 연결 실패"));
        metrics.refresh();

        assertThat(gauge("DEAD")).isEqualTo(2);
    }

    private double gauge(String status) {
        return registry.get("notification.outbox.messages").tag("status", status).gauge().value();
    }

    private static NotificationOutboxRepository.StatusCountView view(OutboxStatus status, long count) {
        return new NotificationOutboxRepository.StatusCountView() {
            public OutboxStatus getStatus() { return status; }
            public long getCount() { return count; }
        };
    }
}
