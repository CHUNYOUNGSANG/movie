package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.service.MovieService;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * 보관 기간이 지난 데이터를 지운다. 지우지 않으면 테이블·인덱스가 계속 커지고,
 * 사용자의 신청 목록(/list, API)은 끝난 신청까지 매번 전부 읽는다.
 *
 * - 상영 일정: 상영일 + retention.schedule-days 가 지나면 (크롤링 결과 upsert 에만 쓰이므로 상영일이 지나면 쓸 일이 없음)
 * - 끝난 신청(매칭됨·만료됨): 상영일 + retention.subscription-days 가 지나면, 그 신청의 Outbox·발송 이력과 함께.
 *   Outbox 를 따로 먼저 지우면 아직 남은 신청의 발송 상태가 비므로, 신청 단위로 함께 지운다.
 *
 * 한 번에 retention.batch-size 건씩, 배치마다 짧은 트랜잭션으로 지운다:
 * 큰 DELETE 한 번은 잠금을 오래 잡고 undo 로그를 키워 크롤링·발송 쓰기를 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionService {

    private final MovieService movieService;
    private final NotificationSubscriptionRepository subscriptionRepository;
    private final OutboxService outboxService;
    private final PlatformTransactionManager transactionManager;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Value("${retention.schedule-days:7}")
    private final int scheduleDays;
    @Value("${retention.subscription-days:90}")
    private final int subscriptionDays;
    @Value("${retention.batch-size:1000}")
    private final int batchSize;

    public record Result(int schedules, int subscriptions, int outbox) {
    }

    public Result purge() {
        LocalDate today = LocalDate.now(clock);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        LocalDate scheduleCutoff = today.minusDays(scheduleDays);
        int schedules = inBatches(() -> movieService.deleteSchedulesShownBefore(scheduleCutoff, batchSize));

        LocalDate subscriptionCutoff = today.minusDays(subscriptionDays);
        int[] outbox = {0};
        int subscriptions = inBatches(() -> tx.execute(status -> {
            List<Long> ids = subscriptionRepository.findPurgeableIds(subscriptionCutoff, batchSize);
            if (ids.isEmpty()) return 0;
            outbox[0] += outboxService.deleteBySubscriptionIds(ids);   // 자식(Outbox·이력) 먼저: FK
            subscriptionRepository.deleteAllByIdInBatch(ids);
            return ids.size();
        }));

        meterRegistry.counter("retention.deleted", "table", "schedules").increment(schedules);
        meterRegistry.counter("retention.deleted", "table", "notification_subscriptions").increment(subscriptions);
        meterRegistry.counter("retention.deleted", "table", "notification_outbox").increment(outbox[0]);
        log.info("보관 기간 지난 데이터 삭제: 상영 일정 {}건(상영일 < {}), 끝난 신청 {}건·Outbox {}건(상영일 < {})",
                schedules, scheduleCutoff, subscriptions, outbox[0], subscriptionCutoff);
        return new Result(schedules, subscriptions, outbox[0]);
    }

    /** 배치가 가득 차지 않을 때까지 반복 (각 배치는 독립된 트랜잭션) */
    private int inBatches(IntSupplier batch) {
        int total = 0;
        while (true) {
            int deleted = batch.getAsInt();
            total += deleted;
            if (deleted < batchSize) return total;
        }
    }
}
