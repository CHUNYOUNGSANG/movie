package io.github.chunyoungsang.moviebot.domain.notification.scheduler;

import io.github.chunyoungsang.moviebot.domain.notification.service.SubscriptionExpiryService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 한 번(서울 기준 00:10) 상영일이 지난 신청을 만료 처리한다. 서버가 여러 대여도 한 대에서만 실행 */
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryScheduler {

    private final SubscriptionExpiryService subscriptionExpiryService;

    @Scheduled(cron = "${notification.expiry.cron:0 10 0 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "subscriptionExpiry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void expire() {
        subscriptionExpiryService.expirePast();
    }
}
