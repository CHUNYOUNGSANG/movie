package global.scheduler;

import domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MovieCheckScheduler {

    private final NotificationService notificationService;

    /**
     * 서버가 여러 대여도 한 대에서만 크롤링한다.
     * - lockAtMostFor: 실행 중 서버가 죽어도 10분 후에는 락이 풀림 (크롤링 최대 소요 시간보다 길게)
     * - lockAtLeastFor: 빨리 끝나도 30초간 락 유지 → 서버 간 시계 차이로 같은 주기를 다른 서버가 또 실행하는 것을 방지
     */
    @Scheduled(cron = "${movie.check.cron:0 */5 * * * *}")
    @SchedulerLock(name = "movieCheck", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void check() {
        log.info("===== 영화 예매 확인 시작 =====");
        notificationService.checkAndNotify();
        log.info("===== 확인 완료 =====");
    }
}
