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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 주기 전체 제한 시간. ShedLock lockAtMostFor(10분)보다 짧아야 한다: 넘기면 락이 풀려 다음 주기와 겹친다.
     * 크롤링 하나의 제한 시간(요청 단위)과 별개로, 그룹이 많거나 CGV 가 느려도 주기는 여기서 끝난다.
     */
    @Value("${movie.crawl.cycle-timeout:PT8M}")
    private final Duration cycleTimeout;

    /** 종료 신호를 받았는지. 받으면 진행 중인 주기는 남은 크롤링을 시작하지 않고 곧바로 끝난다 */
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** 진행 중인 주기의 크롤링 작업 (종료 신호를 받으면 시작 안 한 것을 바로 취소) */
    private volatile List<Future<CrawlOutcome>> inFlight = List.of();

    private static final long POLL_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    /**
     * 크롤링(외부 I/O, 수 초 소요)은 트랜잭션 밖에서 수행하고,
     * DB 반영은 MovieService / NotificationStateService 의 짧은 트랜잭션으로 나눈다.
     * → 크롤링 동안 DB 커넥션을 점유하지 않고, 한 그룹의 실패가 이미 처리된 그룹을 롤백시키지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void checkAndNotify() {
        if (stopping.get()) return;   // 종료 중에는 새 주기를 시작하지 않는다
        List<PendingGroup> groups = notificationStateService.findPendingGroups();   // 영화|지점|날짜 (같은 조건은 한 번만 크롤링)
        if (groups.isEmpty()) {
            log.info("대기 중인 알림 신청 없음");
            return;
        }
        log.info("크롤링 그룹 {}개 확인 시작", groups.size());

        // 제한 시간에 걸리면 뒤쪽 그룹이 밀린다 → 매 주기 순서를 섞어 같은 그룹만 계속 밀리지 않게 한다
        List<PendingGroup> order = new ArrayList<>(groups);
        Collections.shuffle(order);

        // 1) 크롤링(외부 I/O)은 크롤링 전용 스레드풀에서 동시에 실행
        CompletionService<CrawlOutcome> completion = new ExecutorCompletionService<>(crawlExecutor);
        List<Future<CrawlOutcome>> futures = new ArrayList<>();
        for (PendingGroup group : order) {
            for (MovieCrawler crawler : crawlers) {
                futures.add(completion.submit(() -> crawl(crawler, group)));
            }
        }
        inFlight = futures;

        // 2) 저장·매칭(DB)은 끝난 순서대로 이 스레드에서 하나씩 처리
        //    여러 스레드가 같은 극장/상영관을 동시에 새로 만들면 유니크 키 충돌이 나므로 병렬로 하지 않는다
        long deadline = System.nanoTime() + cycleTimeout.toNanos();
        try {
            for (int i = 0; i < futures.size(); i++) {
                Future<CrawlOutcome> done = pollUntil(completion, deadline);
                if (done == null) {
                    stopEarly(futures, i, stopping.get() ? "종료 신호" : "주기 제한 시간(" + cycleTimeout + ") 초과");
                    return;
                }
                CrawlOutcome outcome = resultQuietly(done);
                if (outcome == null || outcome.results().isEmpty()) continue;
                movieService.saveScreenings(outcome.results());                               // 짧은 트랜잭션
                notificationStateService.markMatched(outcome.group(), outcome.results());   // 짧은 트랜잭션, 이 그룹의 신청만 읽음
            }
        } finally {
            inFlight = List.of();
        }
    }

    /**
     * 애플리케이션 종료(배포, docker stop) 시작 시 호출된다. 진행 중인 주기가 남은 크롤링을 시작하지 않고 곧바로 끝나게 한다.
     * 주기가 끝나야 ShedLock 락이 풀린다: 강제 종료(SIGKILL)될 때까지 주기가 남아 있으면
     * 락이 lockAtMostFor(10분)까지 남아, 새로 뜬 서버가 그동안 크롤링을 시작하지 못한다.
     * 신청은 대기 중 그대로이므로 다음 서버의 첫 주기에 다시 크롤링된다.
     */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        stopping.set(true);
        inFlight.forEach(f -> f.cancel(false));   // 시작 안 한 것은 실행되지 않음, 실행 중인 것은 끝까지 (브라우저 세션 보호)
    }

    /**
     * 제한 시간 초과 또는 종료 신호: 아직 시작하지 않은 크롤링은 취소한다 (큐에서 꺼내져도 실행되지 않음).
     * 실행 중인 크롤링은 인터럽트하지 않는다: 스레드마다 브라우저 세션을 재사용하므로 끊으면 세션이 깨질 수 있고,
     * 요청 단위 제한 시간이 있어 곧 끝난다. 그 결과는 버린다 (신청은 그대로 대기 중 → 다음 주기에 다시 크롤링).
     * 지표 crawl.cycle.timeout: 제한 시간에 걸린 주기 수 (자주 늘면 동시 크롤링 수·주기·제한 시간 조정 필요)
     */
    private void stopEarly(List<Future<CrawlOutcome>> futures, int processed, String reason) {
        futures.forEach(f -> f.cancel(false));
        long unfinished = futures.stream().filter(Future::isCancelled).count();
        if (!stopping.get()) meterRegistry.counter("crawl.cycle.timeout").increment();
        log.warn("크롤링 주기 중단({}): {}/{}건 처리, 끝나지 않은 크롤링 {}건은 다음 주기에 다시 시도",
                reason, processed, futures.size(), unfinished);
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

    /** 다음으로 끝난 크롤링. 제한 시간까지 아무것도 끝나지 않거나 종료 신호를 받으면 null (종료 신호는 0.2초 안에 반영) */
    private Future<CrawlOutcome> pollUntil(CompletionService<CrawlOutcome> completion, long deadline) {
        try {
            while (!stopping.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                Future<CrawlOutcome> done = completion.poll(Math.min(remaining, POLL_SLICE_NANOS), TimeUnit.NANOSECONDS);
                if (done != null) return done;
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("크롤링 결과 대기 중 인터럽트", e);
        }
    }

    /** 크롤링 하나가 실패해도 나머지 그룹은 계속 처리한다 */
    private static CrawlOutcome resultQuietly(Future<CrawlOutcome> done) {
        // 완료 큐에 들어왔어도 실제로는 끝나지 않은 작업일 수 있다: 스레드풀이 종료되며 버린 작업은
        // ExecutorCompletionService 가 감싼 바깥 Future 만 취소된 채 큐에 넣고, 안쪽 작업은 실행도 취소도 되지 않는다.
        // 그 상태에서 get() 하면 영원히 기다린다 → 끝나지 않은 작업은 결과 없음으로 본다.
        if (!done.isDone() || done.isCancelled()) return null;
        try {
            return done.get();   // 끝난 작업이라 기다리지 않음
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("크롤링 결과 대기 중 인터럽트", e);
        } catch (ExecutionException e) {
            log.error("크롤링 실패: {}", String.valueOf(e.getCause()));
            return null;
        }
    }
}
