package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.movie.service.MovieService;
import io.github.chunyoungsang.moviebot.domain.notification.dto.PendingGroup;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;

/**
 * 예매 오픈 확인: 대기 중인 알림 신청을 조건별로 묶어 크롤링하고, 결과를 저장·매칭한다.
 * 알림 신청 관리(NotificationService)와 바뀌는 이유가 달라 분리했다 (크롤링·병렬화·지표 vs 신청 규칙).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingCheckService {

    private final NotificationStateService notificationStateService;
    private final MovieService movieService;     // 상영 스케줄 저장은 Movie 도메인 책임
    private final List<MovieCrawler> crawlers;   // OCP: 새 크롤러 추가 시 이 코드 수정 없음
    @Qualifier("crawlExecutor")
    private final Executor crawlExecutor;
    private final MeterRegistry meterRegistry;

    /**
     * 크롤링(외부 I/O, 수 초 소요)은 트랜잭션 밖에서 수행하고,
     * DB 반영은 MovieService / NotificationStateService 의 짧은 트랜잭션으로 나눈다.
     * → 크롤링 동안 DB 커넥션을 점유하지 않고, 한 그룹의 실패가 이미 처리된 그룹을 롤백시키지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void checkAndNotify() {
        List<PendingGroup> groups = notificationStateService.findPendingGroups();   // 영화|지점|날짜 (같은 조건은 한 번만 크롤링)
        if (groups.isEmpty()) {
            log.info("대기 중인 알림 신청 없음");
            return;
        }
        log.info("크롤링 그룹 {}개 확인 시작", groups.size());

        // 1) 크롤링(외부 I/O)은 크롤링 전용 스레드풀에서 동시에 실행
        CompletionService<CrawlOutcome> completion = new ExecutorCompletionService<>(crawlExecutor);
        int submitted = 0;
        for (PendingGroup group : groups) {
            for (MovieCrawler crawler : crawlers) {
                completion.submit(() -> crawl(crawler, group));
                submitted++;
            }
        }

        // 2) 저장·매칭(DB)은 끝난 순서대로 이 스레드에서 하나씩 처리
        //    여러 스레드가 같은 극장/상영관을 동시에 새로 만들면 유니크 키 충돌이 나므로 병렬로 하지 않는다
        for (int i = 0; i < submitted; i++) {
            CrawlOutcome outcome = takeQuietly(completion);
            if (outcome == null || outcome.results().isEmpty()) continue;
            movieService.saveScreenings(outcome.results());                               // 짧은 트랜잭션
            notificationStateService.markMatched(outcome.group(), outcome.results());   // 짧은 트랜잭션, 이 그룹의 신청만 읽음
        }
    }

    // ── private ───────────────────────────────────────────────

    private record CrawlOutcome(PendingGroup group, List<ScreeningResult> results) {
    }

    /**
     * 크롤링만 수행 (트랜잭션 없음, 크롤링 스레드에서 실행)
     * 지표 crawl.duration{cinema, outcome=found|empty|error}: 크롤링 한 번의 소요 시간과 결과
     */
    private CrawlOutcome crawl(MovieCrawler crawler, PendingGroup group) {
        String movieTitle = group.movieTitle();
        String siteHint = group.branchName();
        LocalDate targetDate = group.targetDate();
        Timer.Sample sample = Timer.start(meterRegistry);
        List<ScreeningResult> results;
        try {
            results = crawler.crawl(movieTitle, siteHint, targetDate);
        } catch (RuntimeException e) {
            sample.stop(crawlTimer(crawler, "error"));
            throw e;
        }
        sample.stop(crawlTimer(crawler, results.isEmpty() ? "empty" : "found"));
        log.info("[{}] {} siteHint={} {} → {}건", crawler.getCinemaName(), movieTitle, siteHint, targetDate, results.size());
        return new CrawlOutcome(group, results);
    }

    private Timer crawlTimer(MovieCrawler crawler, String outcome) {
        return Timer.builder("crawl.duration")
                .description("크롤링 한 번(영화|지점|날짜 그룹)의 소요 시간과 결과")
                .tag("cinema", Objects.requireNonNullElse(crawler.getCinemaName(), "unknown"))
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    /** 크롤링 하나가 실패해도 나머지 그룹은 계속 처리한다 */
    private static CrawlOutcome takeQuietly(CompletionService<CrawlOutcome> completion) {
        try {
            return completion.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("크롤링 결과 대기 중 인터럽트", e);
        } catch (ExecutionException e) {
            log.error("크롤링 실패: {}", String.valueOf(e.getCause()));
            return null;
        }
    }
}
