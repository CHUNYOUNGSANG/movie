package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.movie.repository.ScheduleRepository;
import io.github.chunyoungsang.moviebot.domain.movie.repository.ScreenTypeRepository;
import io.github.chunyoungsang.moviebot.domain.movie.repository.TheaterRepository;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.NotificationOutboxRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 4순위 개선 과제 재현: 크롤링이 그룹(영화|지점|날짜)마다 순서대로 실행되어
 * 전체 소요 시간이 그룹 수에 비례하는 문제.
 *
 * 실제 CGV 대신 1회 500ms 가 걸리는 가짜 크롤러를 사용한다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "movie.crawl.concurrency=3"
})
@DisplayName("크롤링 병렬화 재현")
class CrawlParallelismTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);
    private static final int GROUPS = 10;
    private static final long CRAWL_MILLIS = 500;
    private static final int CONCURRENCY = 3;

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private BookingCheckService bookingCheckService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private TheaterRepository theaterRepository;
    @Autowired private ScreenTypeRepository screenTypeRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private NotificationHistoryRepository historyRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        theaterRepository.deleteAllInBatch();
        screenTypeRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[병렬 크롤링] 서로 다른 그룹 10개는 설정한 동시 실행 수(3)만큼 함께 크롤링하고, 매칭 결과는 같아야 한다")
    void crawlsGroupsConcurrently() {
        User user = userRepository.save(User.builder().chatId("6001").username("u6001").build());
        for (int i = 0; i < GROUPS; i++) {
            String title = "영화" + i;
            Movie movie = movieRepository.save(Movie.builder().movieId(title).title(title).build());
            subscriptionRepository.save(NotificationSubscription.builder()
                    .user(user).movie(movie).targetDate(TARGET_DATE).build());
        }

        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willAnswer(inv -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(CRAWL_MILLIS);   // 크롤링(네트워크 왕복) 시간
            } finally {
                running.decrementAndGet();
            }
            String title = inv.getArgument(0);
            return List.of(new ScreeningResult("CGV", "CGV 강남 1관", title, TARGET_DATE,
                    LocalTime.of(19, 0), 100, true, "https://cgv.co.kr/ticket?siteNo=0056"));
        });

        long start = System.currentTimeMillis();
        bookingCheckService.checkAndNotify();
        long elapsed = System.currentTimeMillis() - start;

        Integer matched = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class);
        System.out.printf("[병렬 크롤링] 그룹 %d개 (1회 %dms) → 소요 %.2f초, 최대 동시 크롤링 %d, 매칭 %d건%n",
                GROUPS, CRAWL_MILLIS, elapsed / 1000.0, maxRunning.get(), matched);

        assertThat(matched).as("매칭되어 발송 대상이 된 알림 수").isEqualTo(GROUPS);
        assertThat(maxRunning.get()).as("동시에 실행된 크롤링 수 (설정값을 넘지 않아야 함)").isEqualTo(CONCURRENCY);
    }
}
