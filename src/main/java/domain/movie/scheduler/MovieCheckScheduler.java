package domain.movie.scheduler;

import domain.movie.service.MovieService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예매 확인 스케줄러 — 시스템의 심장 역할.
 * SRP : 주기적 트리거만 담당. 로직은 MovieService에 위임.
 * DIP : MovieService 인터페이스에 의존.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MovieCheckScheduler {

    private final MovieService movieService;

    @Scheduled(cron = "${movie.check.cron:0 */5 * * * *}")
    public void check() {
        log.info("===== 영화 예매 확인 시작 =====");
        movieService.checkAndNotify();
        log.info("===== 확인 완료 =====");
    }
}