package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.support.TestDates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 크롤링 주기 도중 애플리케이션이 종료될 때 (배포, docker stop).
 *
 * 종료 신호가 와도 주기는 그 사실을 모른 채 남은 크롤링을 기다린다 (주기 제한 시간까지).
 * docker stop 은 기본 10초 뒤 강제 종료(SIGKILL)하므로, 주기가 끝나지 못하면 ShedLock 락이 풀리지 않고
 * 다음 서버(재배포된 서버)는 lockAtMostFor(10분)까지 크롤링을 시작하지 못한다.
 * 여기서는 시간을 줄여 재현한다: 동시 크롤링 1개, 그룹 10개 × 300ms, 주기 제한 5초, 시작 0.5초 뒤 종료.
 * 개선 전 코드에서는 실패하는 것이 정상이며, 출력([정상 종료] ...)이 곧 재현 결과다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.expiry.cron=-",
        "retention.cron=-",
        "movie.crawl.concurrency=1",
        "movie.crawl.cycle-timeout=5s"
})
@DirtiesContext   // 이 테스트가 크롤링 스레드풀을 종료한다
@DisplayName("크롤링 중 종료 재현")
class ShutdownDuringCrawlTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);
    }

    private static final int GROUPS = 10;
    private static final long CRAWL_MS = 300;

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private BookingCheckService bookingCheckService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConfigurableApplicationContext context;
    @Autowired @Qualifier("crawlExecutor") private ThreadPoolTaskExecutor crawlExecutor;

    @Test
    @DisplayName("[정상 종료] 종료 신호가 오면 남은 크롤링을 시작하지 않고 주기를 곧바로 끝낸다")
    void cycleEndsPromptlyOnShutdown() throws InterruptedException {
        seed();
        AtomicLong startedAfterShutdown = new AtomicLong();
        AtomicLong shutdownAt = new AtomicLong(Long.MAX_VALUE);
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willAnswer(i -> {
            if (System.nanoTime() > shutdownAt.get()) startedAfterShutdown.incrementAndGet();
            Thread.sleep(CRAWL_MS);
            return List.of();
        });

        AtomicLong cycleEndedAt = new AtomicLong();
        Thread cycle = new Thread(() -> {
            try {
                bookingCheckService.checkAndNotify();
            } catch (RuntimeException ignored) {
                // 종료 중 DB 커넥션 풀이 닫혀 실패할 수 있음 (그 자체가 재현 결과의 일부)
            } finally {
                cycleEndedAt.set(System.nanoTime());
            }
        });
        cycle.setDaemon(true);   // 끝나지 않아도 테스트 JVM 종료를 막지 않게
        cycle.start();
        Thread.sleep(500);

        shutdownAt.set(System.nanoTime());
        // SIGTERM 을 받았을 때 Spring 이 하는 순서 그대로: 종료 시작 이벤트 → (빈 정리 단계) 크롤링 스레드풀 종료.
        // 테스트 컨텍스트 자체를 닫으면 테스트 프레임워크가 닫힌 컨텍스트를 다시 쓰려다 실패하므로 두 단계를 직접 실행한다
        context.publishEvent(new ContextClosedEvent(context));
        crawlExecutor.destroy();
        cycle.join(15_000);

        boolean ended = !cycle.isAlive();
        long afterShutdownMs = ended ? (cycleEndedAt.get() - shutdownAt.get()) / 1_000_000 : -1;
        long crawled = Mockito.mockingDetails(crawler).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("crawl")).count();
        System.out.printf("[정상 종료] 종료 신호 후 주기가 끝나기까지 %s, 종료 신호 후 새로 시작한 크롤링 %d회 (전체 %d/%d)%n",
                ended ? String.format("%,dms", afterShutdownMs) : "15초 넘게 끝나지 않음", startedAfterShutdown.get(), crawled, GROUPS);

        assertThat(ended).as("주기가 끝난다").isTrue();
        assertThat(afterShutdownMs).as("docker stop 유예(10초)보다 훨씬 빨리 끝난다").isLessThan(CRAWL_MS * 3);
        assertThat(startedAfterShutdown.get()).as("종료 신호 후에는 새 크롤링을 시작하지 않는다").isZero();
    }

    private void seed() {
        jdbc.update("INSERT INTO users (user_id, chat_id, username, active) VALUES (1, 'c1', 'u1', TRUE)");
        List<Object[]> movies = new ArrayList<>();
        List<Object[]> subs = new ArrayList<>();
        for (int m = 1; m <= GROUPS; m++) {
            movies.add(new Object[]{"영화" + m, "영화" + m});
            subs.add(new Object[]{"영화" + m, Date.valueOf(TestDates.future())});
        }
        jdbc.batchUpdate("INSERT INTO movies (movie_id, title) VALUES (?, ?)", movies);
        jdbc.batchUpdate("INSERT INTO notification_subscriptions (user_id, movie_id, target_date, is_triggered) VALUES (1, ?, ?, FALSE)", subs);
    }
}
