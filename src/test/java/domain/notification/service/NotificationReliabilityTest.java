package domain.notification.service;

import com.zaxxer.hikari.HikariDataSource;
import domain.TestApplication;
import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScheduleRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import domain.notification.entity.NotificationHistory;
import domain.notification.entity.NotificationSubscription;
import domain.notification.repository.NotificationHistoryRepository;
import domain.notification.repository.NotificationSubscriptionRepository;
import domain.user.entity.User;
import domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 1순위 개선 과제 재현 테스트: checkAndNotify() 의 트랜잭션 범위 / 알림 신뢰성.
 *
 * 각 테스트는 "개선 후 기대 동작"을 검증한다. 개선 전 코드에서는 실패하는 것이 정상이며,
 * 실패 메시지가 곧 문제의 재현 결과다.
 *
 * - 실제 텔레그램 호출을 막기 위해 RestTemplate 을 mock 으로 교체한다.
 *   TelegramService 자체는 실제 빈을 사용해 @Async 와 예외를 삼키는 동작을 그대로 재현한다.
 * - 크롤러(Playwright)는 mock 으로 교체한다.
 * - 실제 커밋/롤백을 봐야 하므로 테스트 클래스에 @Transactional 을 붙이지 않는다.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "telegram.bot.token=test-token",   // "YOUR_" 로 시작하지 않아야 발송 로직이 실행됨
        "movie.check.cron=-"               // 스케줄러 자동 실행 비활성화 (테스트에서 직접 호출)
})
@DisplayName("알림 신뢰성 / 트랜잭션 범위 재현")
class NotificationReliabilityTest {

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationStateService notificationStateService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSource dataSource;

    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private TheaterRepository theaterRepository;
    @Autowired private ScreenTypeRepository screenTypeRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private NotificationHistoryRepository historyRepository;

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);

    @BeforeEach
    void setUp() {
        given(crawler.getCinemaName()).willReturn("CGV");
    }

    @AfterEach
    void cleanUp() {
        historyRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        theaterRepository.deleteAllInBatch();
        screenTypeRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[유실] 텔레그램 발송이 실패하면 구독은 미발송 상태로 남아 다음 주기에 재시도되어야 한다")
    void sendFailureMustNotMarkSubscriptionAsDone() {
        NotificationSubscription sub = subscribe("1001", "위키드");
        given(crawler.crawl(anyString(), any(), any()))
                .willAnswer(inv -> List.of(openScreening(inv.getArgument(0))));
        given(restTemplate.postForObject(sendMessageUrl(), any(), eq(Map.class)))
                .willThrow(new RestClientException("텔레그램 API 장애 (테스트)"));

        notificationService.checkAndNotify();

        // 비동기 발송 시도가 끝날 때까지 대기
        verify(restTemplate, timeout(3000)).postForObject(sendMessageUrl(), any(), eq(Map.class));
        waitUntil(() -> historyRepository.count() > 0, 3000);

        NotificationSubscription reloaded = subscriptionRepository.findById(sub.getSubscriptionId()).orElseThrow();
        List<String> statuses = historyRepository.findAll().stream().map(NotificationHistory::getStatus).toList();

        assertThat(statuses)
                .as("발송에 실패했는데 이력이 SUCCESS 로 기록됨")
                .doesNotContain("SUCCESS");
        assertThat(reloaded.isTriggered())
                .as("발송에 실패했는데 triggered=true 로 저장되어 다시는 재시도되지 않음 (알림 유실)")
                .isFalse();
    }

    @Test
    @DisplayName("[중복] 처리 도중 예외로 롤백되면, 이미 발송된 알림이 다음 주기에 다시 발송되면 안 된다")
    void rollbackMustNotCauseDuplicateAlert() {
        subscribe("2001", "위키드");
        subscribe("2002", "주토피아 2");

        // 1주기: 첫 번째 그룹은 정상 크롤링 → 알림 발송, 두 번째 그룹 크롤링에서 예외 발생 → 전체 롤백
        // 2주기: 모두 정상
        AtomicInteger calls = new AtomicInteger();
        given(crawler.crawl(anyString(), any(), any())).willAnswer(inv -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("CGV 응답 파싱 실패 (테스트)");
            }
            return List.of(openScreening(inv.getArgument(0)));
        });

        assertThatThrownBy(() -> notificationService.checkAndNotify())
                .isInstanceOf(IllegalStateException.class);
        notificationService.checkAndNotify();

        // 비동기 발송이 모두 끝날 때까지 기다린 뒤 chatId 별 발송 횟수를 확인
        verify(restTemplate, after(2000).times(1)).postForObject(sendMessageUrl(), chatIdIs("2001"), eq(Map.class));
        verify(restTemplate, times(1)).postForObject(sendMessageUrl(), chatIdIs("2002"), eq(Map.class));
    }

    @Test
    @DisplayName("[커밋 전 발송] 매칭 처리 트랜잭션이 롤백되면 알림을 발송하지 않아야 한다")
    void rolledBackMatchMustNotSendAlert() {
        NotificationSubscription sub = subscribe("4001", "위키드");

        // markMatched() 이후 같은 트랜잭션에서 예외가 난 상황 (예: 커밋 시점 제약조건 위반)
        transactionTemplate.executeWithoutResult(status -> {
            notificationStateService.markMatched(List.of(sub.getSubscriptionId()), List.of(openScreening("위키드")));
            status.setRollbackOnly();
        });

        verify(restTemplate, after(1000).never()).postForObject(sendMessageUrl(), any(), eq(Map.class));
        assertThat(subscriptionRepository.findById(sub.getSubscriptionId()).orElseThrow().isTriggered())
                .as("롤백되었으므로 다음 주기에 다시 처리되어야 함")
                .isFalse();
    }

    @Test
    @DisplayName("[커넥션 점유] 크롤링(외부 I/O) 동안에는 DB 커넥션을 점유하지 않아야 한다")
    void crawlingMustNotHoldDbConnection() throws Exception {
        subscribe("3001", "위키드");
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        AtomicInteger activeDuringCrawl = new AtomicInteger(-1);
        given(crawler.crawl(anyString(), any(), any())).willAnswer(inv -> {
            activeDuringCrawl.set(hikari.getHikariPoolMXBean().getActiveConnections());
            return List.of();
        });

        notificationService.checkAndNotify();

        assertThat(activeDuringCrawl.get())
                .as("크롤링 중 사용 중인 DB 커넥션 수 (Playwright 크롤링은 수 초가 걸리므로 그동안 커넥션이 묶임)")
                .isZero();
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
                TARGET_DATE, LocalTime.of(19, 0), 100, true,
                "https://cgv.co.kr/ticket?siteNo=0056");
    }

    private static String sendMessageUrl() {
        return argThat(url -> url != null && url.endsWith("/sendMessage"));
    }

    @SuppressWarnings("unchecked")
    private static Object chatIdIs(String chatId) {
        return argThat(body -> body instanceof Map<?, ?> m && chatId.equals(m.get("chat_id")));
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
