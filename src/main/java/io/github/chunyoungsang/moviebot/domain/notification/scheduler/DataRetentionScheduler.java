package io.github.chunyoungsang.moviebot.domain.notification.scheduler;

import io.github.chunyoungsang.moviebot.domain.notification.service.DataRetentionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 보관 기간이 지난 데이터 정리. 사용이 적은 새벽(서울 03:30)에 한 서버에서만 */
@Component
@RequiredArgsConstructor
public class DataRetentionScheduler {

    private final DataRetentionService dataRetentionService;

    @Scheduled(cron = "${retention.cron:0 30 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "dataRetention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        dataRetentionService.purge();
    }
}
