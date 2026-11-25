package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.movie.repository.ScheduleRepository;
import io.github.chunyoungsang.moviebot.domain.movie.repository.ScreenTypeRepository;
import io.github.chunyoungsang.moviebot.domain.movie.repository.TheaterRepository;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.OutboxStatus;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.repository.NotificationOutboxRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 구독을 해제했거나(/stop) 봇을 차단한 사용자에게 알림이 가는 문제 재현.
 *
 * 각 테스트는 "개선 후 기대 동작"을 검증한다. 개선 전 코드에서는 실패하는 것이 정상이며,
 * 출력([비활성 사용자] ...)이 곧 문제의 재현 결과다.
 * 텔레그램은 RestTemplate mock, CGV 는 MovieCrawler mock 으로 대체한다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.outbox.relay-delay-ms=200",
        "notification.outbox.base-delay-ms=100"
})
@DisplayName("비활성 사용자 알림 재현")
class InactiveUserAlertTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private BookingCheckService bookingCheckService;
    @Autowired private UserService userService;
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
    @DisplayName("[/stop] 구독을 해제한 사용자의 신청은 크롤링하지 않고, 알림도 보내지 않는다")
    void stoppedUserIsNeitherCrawledNorAlerted() throws Exception {
        subscribe("8001", "위키드");
        subscribe("8002", "파묘");
        userService.deactivate("8002");                       // /stop
        crawlerAlwaysFinds();

        bookingCheckService.checkAndNotify();
        Thread.sleep(1000);                                    // 즉시 발송 + relay 가 돌 시간

        int crawled = crawlCount("파묘");
        int sent = sendCount("8002");
        System.out.printf("[비활성 사용자] /stop 한 사용자 → 크롤링 %d회, 발송 %d건 (활성 사용자 발송 %d건)%n",
                crawled, sent, sendCount("8001"));

        assertThat(sendCount("8001")).isEqualTo(1);           // 활성 사용자는 그대로 받는다
        assertThat(crawled).isZero();
        assertThat(sent).isZero();
    }

    @Test
    @DisplayName("[재시도 중 /stop] 첫 발송이 실패한 뒤 구독을 해제하면 재시도로 보내지 않는다")
    void stoppedWhileRetryingIsNotSent() throws Exception {
        subscribe("8101", "위키드");
        crawlerAlwaysFinds();
        // 첫 발송: 텔레그램 일시 장애(502) → 재시도 대기. 그 사이에 사용자가 /stop
        willAnswer(inv -> {
            userService.deactivate("8101");
            throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        }).willAnswer(inv -> Map.of("ok", true))
                .given(restTemplate).postForObject(anyString(), any(), eq(Map.class));

        bookingCheckService.checkAndNotify();
        Thread.sleep(2000);                                    // 재시도 대기(100ms~) + relay(200ms) 여러 번

        int attempts = sendCount("8101");
        OutboxStatus status = outboxRepository.findAll().get(0).getStatus();
        System.out.printf("[비활성 사용자] 재시도 대기 중 /stop → 발송 시도 %d회 (첫 실패 포함), Outbox %s%n", attempts, status);

        assertThat(attempts).isEqualTo(1);                     // 실패한 첫 시도만
        assertThat(status.isFinished()).isTrue();              // 대기열에 남아 있지 않는다
    }

    @Test
    @DisplayName("[봇 차단] 텔레그램이 403(차단)을 주면 사용자를 비활성으로 바꾸고, 다음 주기부터 크롤링하지 않는다")
    void blockedUserIsDeactivated() throws Exception {
        subscribe("8201", "위키드");
        subscribe("8201", "파묘");                             // 같은 사용자의 다른 신청
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(eq("위키드"), any(), any())).willReturn(List.of(screening("위키드")));
        given(crawler.crawl(eq("파묘"), any(), any())).willReturn(List.of());   // 아직 예매 안 열림
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class))).willThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        bookingCheckService.checkAndNotify();
        await().atMost(Duration.ofSeconds(3)).until(() -> sendCount("8201") >= 1);
        Thread.sleep(500);
        Mockito.clearInvocations(crawler);

        bookingCheckService.checkAndNotify();                  // 다음 주기
        boolean active = userRepository.findByChatId("8201").orElseThrow().isActive();
        int crawledNext = crawlCount("파묘");
        System.out.printf("[비활성 사용자] 봇 차단(403) 후 → 사용자 활성=%s, 다음 주기 크롤링 %d회%n", active, crawledNext);

        assertThat(active).isFalse();
        assertThat(crawledNext).isZero();
    }

    // ── helpers ───────────────────────────────────────────────

    private void subscribe(String chatId, String title) {
        User user = userRepository.findByChatId(chatId)
                .orElseGet(() -> userRepository.save(User.builder().chatId(chatId).username("u" + chatId).build()));
        Movie movie = movieRepository.findById(title)
                .orElseGet(() -> movieRepository.save(Movie.builder().movieId(title).title(title).build()));
        subscriptionRepository.save(NotificationSubscription.builder().user(user).movie(movie).targetDate(TARGET_DATE).build());
    }

    private void crawlerAlwaysFinds() {
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willAnswer(inv -> List.of(screening(inv.getArgument(0))));
    }

    private static ScreeningResult screening(String title) {
        return new ScreeningResult("CGV", "CGV 강남 1관", title, TARGET_DATE, LocalTime.of(19, 0), 100, true,
                "https://cgv.co.kr/ticket?siteNo=0056");
    }

    private int crawlCount(String title) {
        return (int) Mockito.mockingDetails(crawler).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("crawl") && title.equals(i.getRawArguments()[0]))
                .count();
    }

    private int sendCount(String chatId) {
        return (int) Mockito.mockingDetails(restTemplate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("postForObject")
                        && String.valueOf(i.getRawArguments()[0]).endsWith("/sendMessage")
                        && i.getRawArguments()[1] instanceof Map<?, ?> body && chatId.equals(body.get("chat_id")))
                .count();
    }
}
