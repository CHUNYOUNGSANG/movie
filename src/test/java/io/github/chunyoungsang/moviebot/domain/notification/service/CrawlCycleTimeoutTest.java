package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.support.TestDates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 크롤링 주기 전체에 제한 시간이 없는 문제 재현.
 *
 * 크롤링 하나에는 요청 제한 시간이 있지만, 주기는 모든 그룹이 끝날 때까지 기다린다(completion.take()).
 * 그룹이 많거나 CGV 가 느리면 주기가 ShedLock 유지 시간(10분)을 넘겨 다음 주기와 겹친다.
 * 여기서는 시간을 줄여 재현한다: 동시 크롤링 1개, 그룹 10개, 크롤링 한 번 300ms, 주기 제한 1초.
 * 개선 전 코드에서는 실패하는 것이 정상이며, 출력([주기 제한] ...)이 곧 재현 결과다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.expiry.cron=-",
        "movie.crawl.concurrency=1",
        "movie.crawl.cycle-timeout=1s"
})
@DisplayName("크롤링 주기 제한 시간 재현")
class CrawlCycleTimeoutTest {

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

    @Test
    @DisplayName("[주기 제한] 주기가 제한 시간을 넘기면 남은 크롤링을 시작하지 않고 끝낸다")
    void cycleStopsAtTimeout() throws InterruptedException {
        seed();
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willAnswer(i -> {
            Thread.sleep(CRAWL_MS);   // 느린 CGV 응답
            return List.of();
        });

        long started = System.nanoTime();
        bookingCheckService.checkAndNotify();
        long cycleMs = (System.nanoTime() - started) / 1_000_000;
        Thread.sleep(CRAWL_MS * 2);   // 주기가 끝난 뒤에도 큐에 남은 크롤링이 계속 도는지 본다
        long crawled = Mockito.mockingDetails(crawler).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("crawl")).count();

        System.out.printf("[주기 제한] 그룹 %d개 × %dms, 제한 1,000ms → 주기 %,dms, 실행된 크롤링 %d회 (나머지 %d개는 다음 주기)%n",
                GROUPS, CRAWL_MS, cycleMs, crawled, GROUPS - crawled);

        assertThat(cycleMs).as("주기는 제한 시간 근처에서 끝난다").isLessThan(1_000 + CRAWL_MS * 2);
        assertThat(crawled).as("제한 시간이 지나면 남은 크롤링을 시작하지 않는다").isLessThan(GROUPS);
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
