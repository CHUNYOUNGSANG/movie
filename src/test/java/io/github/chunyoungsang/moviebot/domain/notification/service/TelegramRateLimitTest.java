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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 4순위 개선 과제 재현: 알림이 한꺼번에 몰릴 때 텔레그램 발송 속도 제한(429) 대응.
 *
 * 텔레그램처럼 동작하는 가짜 서버: 1초에 30건을 넘으면 429(retry_after=1)를 주고,
 * 그 1초 동안은 모든 요청을 거부한다(flood control).
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.outbox.relay-delay-ms=200",
        "notification.outbox.base-delay-ms=100",   // 재시도 대기를 줄여 테스트 시간 단축 (운영 30초)
        "notification.outbox.max-attempts=5"
})
@DisplayName("텔레그램 발송 속도 제한 재현")
class TelegramRateLimitTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);
    private static final int USERS = 100;

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
    @DisplayName("[속도 제한] 알림 100건이 한꺼번에 몰려도 429 없이 모두 발송되고, 발송 포기(DEAD)가 없어야 한다")
    void burstOfAlertsIsDeliveredWithoutRateLimitErrors() throws InterruptedException {
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        for (int i = 0; i < USERS; i++) {
            User user = userRepository.save(User.builder().chatId(String.valueOf(10_000 + i)).username("u" + i).build());
            subscriptionRepository.save(NotificationSubscription.builder()
                    .user(user).movie(movie).targetDate(TARGET_DATE).build());
        }
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willReturn(List.of(new ScreeningResult(
                "CGV", "CGV 강남 1관", "위키드", TARGET_DATE, LocalTime.of(19, 0), 100, true,
                "https://cgv.co.kr/ticket?siteNo=0056")));
        FakeTelegram telegram = new FakeTelegram(30);
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class))).willAnswer(inv -> telegram.handle(inv.getArgument(0)));

        long start = System.currentTimeMillis();
        bookingCheckService.checkAndNotify();
        int remaining = waitUntilSettled(30_000);
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = jdbcTemplate.queryForMap("""
                SELECT SUM(status = 'SENT') AS sent, SUM(status = 'DEAD') AS dead
                FROM notification_outbox
                """);
        long sent = ((Number) result.get("sent")).longValue();
        long dead = ((Number) result.get("dead")).longValue();
        System.out.printf("[속도 제한] 알림 %d건 → 발송 %d, 포기(DEAD) %d, 미처리 %d, 429 응답 %d회, 소요 %.1f초%n",
                USERS, sent, dead, remaining, telegram.rejected.get(), elapsed / 1000.0);

        assertThat(dead).as("속도 제한 때문에 발송을 포기한 알림 수").isZero();
        assertThat(sent).as("발송 완료된 알림 수").isEqualTo(USERS);
        assertThat(telegram.rejected.get()).as("텔레그램이 429 로 거부한 요청 수").isZero();
    }

    /** 모든 Outbox 가 SENT/DEAD 가 될 때까지 대기. 남은 건수를 반환 */
    private int waitUntilSettled(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int remaining;
        do {
            Thread.sleep(200);
            remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_outbox WHERE status IN ('PENDING', 'SENDING')", Integer.class);
        } while (remaining > 0 && System.currentTimeMillis() < deadline);
        return remaining;
    }

    /** 텔레그램 Bot API 의 전역 발송 제한(초당 약 30건)과 flood control 을 흉내 낸다. */
    static class FakeTelegram {
        private final int limitPerSecond;
        private final Deque<Long> sentAt = new ArrayDeque<>();
        private long blockedUntil = 0;
        final AtomicInteger rejected = new AtomicInteger();

        FakeTelegram(int limitPerSecond) {
            this.limitPerSecond = limitPerSecond;
        }

        synchronized Map<String, Object> handle(String url) {
            if (!url.endsWith("/sendMessage")) return Map.of("ok", true);
            long now = System.currentTimeMillis();
            while (!sentAt.isEmpty() && sentAt.peekFirst() <= now - 1000) sentAt.pollFirst();
            if (now < blockedUntil || sentAt.size() >= limitPerSecond) {
                blockedUntil = Math.max(blockedUntil, now + 1000);
                rejected.incrementAndGet();
                throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        new HttpHeaders(),
                        """
                        {"ok":false,"error_code":429,"description":"Too Many Requests: retry after 1","parameters":{"retry_after":1}}
                        """.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            sentAt.addLast(now);
            return Map.of("ok", true);
        }
    }
}
