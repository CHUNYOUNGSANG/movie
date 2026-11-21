package io.github.chunyoungsang.moviebot.notification.service;

import io.github.chunyoungsang.moviebot.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.movie.repository.ScheduleRepository;
import io.github.chunyoungsang.moviebot.movie.repository.ScreenTypeRepository;
import io.github.chunyoungsang.moviebot.movie.repository.TheaterRepository;
import io.github.chunyoungsang.moviebot.notification.event.NotificationSendListener;
import io.github.chunyoungsang.moviebot.notification.outbox.NotificationOutboxRepository;
import io.github.chunyoungsang.moviebot.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.user.entity.User;
import io.github.chunyoungsang.moviebot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 커밋 직후 ~ 발송 전 구간의 유실 재현.
 *
 * AFTER_COMMIT 리스너를 mock 으로 바꿔 "커밋은 됐는데 리스너가 발송하지 못한 상황"
 * (서버 재시작, 스레드풀 대기열 초과로 작업 거부 등)을 만든다.
 * 이 경우에도 결국 알림이 발송되어야 한다.
 */
@SpringBootTest(properties = {
        // 테스트 클래스마다 독립된 DB (다른 컨텍스트의 relay 가 Outbox 를 집어가지 않도록)
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.outbox.relay-delay-ms=200"
})
@DisplayName("커밋 후 발송 유실 복구 재현")
class NotificationRecoveryTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;
    @MockitoBean private NotificationSendListener sendListener;   // 리스너가 동작하지 못한 상황

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

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);

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
    @DisplayName("[커밋 후 유실] 커밋 직후 발송 리스너가 실행되지 못해도 알림은 결국 발송되어야 한다")
    void alertIsEventuallySentEvenIfListenerIsLost() {
        User user = userRepository.save(User.builder().chatId("7001").username("u7001").build());
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        subscriptionRepository.save(io.github.chunyoungsang.moviebot.notification.entity.NotificationSubscription.builder()
                .user(user).movie(movie).targetDate(TARGET_DATE).build());

        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willReturn(List.of(new ScreeningResult(
                "CGV", "CGV 강남 1관", "위키드", TARGET_DATE, LocalTime.of(19, 0), 100, true,
                "https://cgv.co.kr/ticket?siteNo=0056")));

        bookingCheckService.checkAndNotify();

        verify(restTemplate, timeout(3000)).postForObject(
                argThat((String url) -> url != null && url.endsWith("/sendMessage")),
                argThat(body -> body instanceof Map<?, ?> m && "7001".equals(m.get("chat_id"))),
                eq(Map.class));
    }
}
