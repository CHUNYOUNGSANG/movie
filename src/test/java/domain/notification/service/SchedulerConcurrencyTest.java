package domain.notification.service;

import domain.TestApplication;
import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScheduleRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import domain.notification.entity.NotificationSubscription;
import domain.notification.outbox.NotificationOutboxRepository;
import domain.notification.repository.NotificationHistoryRepository;
import domain.notification.repository.NotificationSubscriptionRepository;
import domain.user.entity.User;
import domain.user.repository.UserRepository;
import global.scheduler.MovieCheckScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import support.MySqlTestContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 3순위 개선 과제 재현: 서버가 여러 대일 때(또는 스케줄이 겹칠 때) 동시 실행 문제.
 * 서버 두 대를 스레드 두 개로 흉내 낸다.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-"
})
@DisplayName("스케줄러 동시 실행 재현")
class SchedulerConcurrencyTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private NotificationStateService notificationStateService;
    @Autowired private MovieCheckScheduler movieCheckScheduler;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private TheaterRepository theaterRepository;
    @Autowired private ScreenTypeRepository screenTypeRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private NotificationHistoryRepository historyRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;

    private final ExecutorService servers = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() {
        given(crawler.getCinemaName()).willReturn("CGV");
    }

    @AfterEach
    void cleanUp() {
        servers.shutdownNow();
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
    @DisplayName("[동시 매칭] 두 서버가 같은 구독을 동시에 매칭해도 발송 대상(Outbox)은 1건만 생겨야 한다")
    void concurrentMatchCreatesSingleOutbox() throws Exception {
        NotificationSubscription sub = subscribe("8001", "위키드");
        List<Long> ids = List.of(sub.getSubscriptionId());
        List<ScreeningResult> results = List.of(openScreening("위키드"));

        CountDownLatch aMatched = new CountDownLatch(1);
        CountDownLatch bMatched = new CountDownLatch(1);

        // 서버 A: 매칭 후 커밋 직전에 잠시 멈춤 (그 사이 서버 B 가 같은 구독을 읽음)
        Future<?> serverA = servers.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            notificationStateService.markMatched(ids, results);
            aMatched.countDown();
            await(bMatched, 1000);
        }));
        // 서버 B: A 가 아직 커밋하지 않은 시점에 같은 구독을 매칭
        Future<?> serverB = servers.submit(() -> {
            await(aMatched, 5000);
            transactionTemplate.executeWithoutResult(status -> {
                notificationStateService.markMatched(ids, results);
                bMatched.countDown();
            });
        });
        serverA.get(10, TimeUnit.SECONDS);
        serverB.get(10, TimeUnit.SECONDS);

        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE subscription_id = ?", Integer.class, sub.getSubscriptionId());
        assertThat(outboxRows).as("같은 구독에 대해 생성된 Outbox 수 (= 사용자가 받게 될 알림 수)").isEqualTo(1);
    }

    @Test
    @DisplayName("[중복 크롤링] 두 서버의 스케줄러가 동시에 실행되어도 크롤링은 한 번만 수행되어야 한다")
    void concurrentSchedulerRunsOnce() throws Exception {
        subscribe("9001", "위키드");
        given(crawler.crawl(anyString(), any(), any())).willAnswer(inv -> {
            Thread.sleep(500);   // 크롤링에 시간이 걸리는 동안 다른 서버도 실행
            return List.of();
        });

        Future<?> serverA = servers.submit(movieCheckScheduler::check);
        Future<?> serverB = servers.submit(movieCheckScheduler::check);
        serverA.get(10, TimeUnit.SECONDS);
        serverB.get(10, TimeUnit.SECONDS);

        verify(crawler, times(1)).crawl(anyString(), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────

    private NotificationSubscription subscribe(String chatId, String movieTitle) {
        User user = userRepository.save(User.builder().chatId(chatId).username("u" + chatId).build());
        Movie movie = movieRepository.save(Movie.builder().movieId(movieTitle).title(movieTitle).build());
        return subscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).targetDate(TARGET_DATE).build());
    }

    private ScreeningResult openScreening(String movieTitle) {
        return new ScreeningResult("CGV", "CGV 강남 1관", movieTitle,
                TARGET_DATE, LocalTime.of(19, 0), 100, true, "https://cgv.co.kr/ticket?siteNo=0056");
    }

    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
