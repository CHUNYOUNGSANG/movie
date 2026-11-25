package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.repository.NotificationOutboxRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.global.exception.BusinessException;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.support.TestDates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 알림 신청의 수명과 입력 검증 재현.
 * - 날짜가 지난 신청은 예매가 열릴 일이 없는데도 5분마다 계속 크롤링된다
 * - 지난 날짜·빈 제목·같은 조건 중복 신청을 그대로 받는다
 *
 * 각 테스트는 "개선 후 기대 동작"을 검증한다. 개선 전 코드에서는 실패하는 것이 정상이며,
 * 출력([신청 수명] ...)이 곧 문제의 재현 결과다. 날짜는 실행 시점 기준 상대값을 쓴다.
 */
@SpringBootTest(properties = {"telegram.bot.token=test-token", "movie.check.cron=-"})
@DisplayName("알림 신청 수명·검증 재현")
class SubscriptionLifecycleTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final String CHAT_ID = "9001";

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private BookingCheckService bookingCheckService;
    @Autowired private NotificationService notificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private NotificationHistoryRepository historyRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder().chatId(CHAT_ID).username("u" + CHAT_ID).build());
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willReturn(List.of());   // 아직 예매 안 열림
    }

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[지난 날짜 크롤링] 상영일이 지난 신청은 크롤링하지 않는다")
    void pastDateIsNotCrawled() {
        save("어제영화", TestDates.today().minusDays(1));
        save("다음달영화", TestDates.future());

        bookingCheckService.checkAndNotify();

        int past = crawlCount("어제영화");
        System.out.printf("[신청 수명] 상영일이 지난 신청 → 크롤링 %d회 (다가오는 신청 %d회)%n", past, crawlCount("다음달영화"));

        assertThat(crawlCount("다음달영화")).isEqualTo(1);
        assertThat(past).isZero();
    }

    @Test
    @DisplayName("[지난 날짜 신청] 오늘보다 이전 날짜로는 신청할 수 없다")
    void pastDateIsRejected() {
        String outcome = register("위키드", TestDates.today().minusDays(1));
        long saved = subscriptionRepository.count();
        System.out.printf("[신청 수명] 어제 날짜로 신청 → %s, 저장된 신청 %d건%n", outcome, saved);

        assertThat(outcome).isEqualTo("TARGET_DATE_PAST");
        assertThat(saved).isZero();
    }

    @Test
    @DisplayName("[빈 입력] 영화 제목이 비었거나 날짜가 없으면 신청할 수 없다 (500 이 아닌 입력 오류)")
    void blankInputIsRejected() {
        String blankTitle = register("  ", TestDates.future());
        String nullTitle = register(null, TestDates.future());
        String nullDate = register("위키드", null);
        System.out.printf("[신청 수명] 빈 제목 → %s, 제목 없음 → %s, 날짜 없음 → %s, 저장된 신청 %d건%n",
                blankTitle, nullTitle, nullDate, subscriptionRepository.count());

        assertThat(List.of(blankTitle, nullTitle, nullDate)).containsOnly("INVALID_SUBSCRIPTION_REQUEST");
        assertThat(subscriptionRepository.count()).isZero();
    }

    @Test
    @DisplayName("[중복 신청] 같은 조건(영화·지점·상영관·날짜)으로 두 번 신청하면 두 번째는 거부한다")
    void duplicateIsRejected() {
        LocalDate date = TestDates.future();
        String first = register("위키드", date);
        String second = register("위키드", date);
        String otherBranch = register("위키드", date, "강남");
        long saved = subscriptionRepository.count();
        System.out.printf("[신청 수명] 같은 조건 두 번 → 첫 번째 %s, 두 번째 %s / 다른 지점 %s, 저장된 신청 %d건%n",
                first, second, otherBranch, saved);

        assertThat(first).isEqualTo("OK");
        assertThat(second).isEqualTo("DUPLICATE_SUBSCRIPTION");
        assertThat(otherBranch).isEqualTo("OK");                  // 조건이 다르면 별개 신청
        assertThat(saved).isEqualTo(2);
    }

    // ── helpers ───────────────────────────────────────────────

    private void save(String title, LocalDate date) {
        Movie movie = movieRepository.save(Movie.builder().movieId(title).title(title).build());
        subscriptionRepository.save(NotificationSubscription.builder().user(user).movie(movie).targetDate(date).build());
    }

    private String register(String title, LocalDate date) {
        return register(title, date, null);
    }

    /** 결과: OK / 비즈니스 오류 코드 / 그 외 예외 이름 */
    private String register(String title, LocalDate date, String branch) {
        try {
            notificationService.register(new NotificationRegisterRequest(CHAT_ID, title, "CGV", branch, null, date));
            return "OK";
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private int crawlCount(String title) {
        return (int) Mockito.mockingDetails(crawler).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("crawl") && title.equals(i.getRawArguments()[0]))
                .count();
    }
}
