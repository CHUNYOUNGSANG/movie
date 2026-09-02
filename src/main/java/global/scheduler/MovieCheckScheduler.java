package global.scheduler;

import domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MovieCheckScheduler {

    private final NotificationService notificationService;

    @Scheduled(cron = "${movie.check.cron:0 */5 * * * *}")
    public void check() {
        log.info("===== 영화 예매 확인 시작 =====");
        notificationService.checkAndNotify();
        log.info("===== 확인 완료 =====");
    }
}
