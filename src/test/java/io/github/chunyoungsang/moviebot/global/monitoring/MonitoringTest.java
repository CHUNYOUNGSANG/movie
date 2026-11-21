package io.github.chunyoungsang.moviebot.global.monitoring;

import io.github.chunyoungsang.moviebot.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.movie.repository.ScheduleRepository;
import io.github.chunyoungsang.moviebot.movie.repository.ScreenTypeRepository;
import io.github.chunyoungsang.moviebot.movie.repository.TheaterRepository;
import io.github.chunyoungsang.moviebot.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.notification.outbox.NotificationOutboxRepository;
import io.github.chunyoungsang.moviebot.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.notification.service.BookingCheckService;
import io.github.chunyoungsang.moviebot.user.entity.User;
import io.github.chunyoungsang.moviebot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 모니터링 재현: 텔레그램 발송이 계속 실패해 알림이 발송 포기(DEAD)로 쌓여도,
 * 운영자가 밖에서 알 수 있는 신호가 로그(WARN) 말고는 없다.
 *
 * 운영자가 쓸 수 있는 HTTP 엔드포인트(헬스 체크, Prometheus 지표)로 상태가 보이는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.outbox.relay-delay-ms=600000",   // 즉시 발송(리스너)만으로 결과가 정해지도록
        "monitoring.outbox.refresh-ms=200"
})
@DisplayName("모니터링 재현")
class MonitoringTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 12, 24);
    private static final int BLOCKED_USERS = 3;

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private BookingCheckService bookingCheckService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Environment environment;

    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private TheaterRepository theaterRepository;
    @Autowired private ScreenTypeRepository screenTypeRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private NotificationHistoryRepository historyRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;

    private final HttpClient http = HttpClient.newHttpClient();

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
    @DisplayName("[헬스 체크] 운영자(로드밸런서, docker)가 앱 상태를 HTTP 로 확인할 수 있어야 한다")
    void healthEndpointIsAvailable() throws Exception {
        HttpResponse<String> health = get("/actuator/health");
        System.out.printf("[모니터링] /actuator/health → %d %s%n", health.statusCode(), health.body());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("[발송 실패 감지] 알림이 발송 포기(DEAD)로 쌓이면 지표로 보여야 한다")
    void deadAlertsAreVisibleAsMetrics() throws Exception {
        // 사용자 3명이 봇을 차단(403) → 재시도해도 성공하지 않으므로 바로 DEAD
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        for (int i = 0; i < BLOCKED_USERS; i++) {
            User user = userRepository.save(User.builder().chatId(String.valueOf(7000 + i)).username("u" + i).build());
            subscriptionRepository.save(NotificationSubscription.builder()
                    .user(user).movie(movie).targetDate(TARGET_DATE).build());
        }
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willReturn(List.of(new ScreeningResult(
                "CGV", "CGV 강남 1관", "위키드", TARGET_DATE, LocalTime.of(19, 0), 100, true,
                "https://cgv.co.kr/ticket?siteNo=0056")));
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class))).willThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        bookingCheckService.checkAndNotify();
        waitUntil(() -> count("SELECT COUNT(*) FROM notification_outbox WHERE status = 'DEAD'") == BLOCKED_USERS);

        // 지표(가 있다면)가 DB 상태를 반영할 시간을 준다
        String metrics = waitForMetric("notification_outbox_messages{", "status=\"DEAD\"", BLOCKED_USERS);
        int deadInDb = count("SELECT COUNT(*) FROM notification_outbox WHERE status = 'DEAD'");
        System.out.printf("[모니터링] DB 의 DEAD %d건 → 지표%n%s%n", deadInDb,
                metrics.isEmpty() ? "  (없음: /actuator/prometheus " + get("/actuator/prometheus").statusCode() + ")" : metrics);
        assertThat(deadInDb).as("재현 조건: DB 에 DEAD 가 쌓였는지").isEqualTo(BLOCKED_USERS);

        assertThat(sample(metrics, "notification_outbox_messages{", "status=\"DEAD\""))
                .as("발송 포기(DEAD) 상태로 남아 있는 알림 수").isEqualTo(BLOCKED_USERS);
        assertThat(sample(metrics, "notification_delivery_seconds_count{", "result=\"dead\""))
                .as("발송 결과가 DEAD 였던 발송 시도 수").isEqualTo(BLOCKED_USERS);
        assertThat(sample(metrics, "crawl_duration_seconds_count{", "outcome=\"found\""))
                .as("상영 정보를 찾은 크롤링 수").isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────

    /** 관리용 포트(따로 띄운 경우) 또는 서비스 포트로 GET */
    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getProperty("local.management.port", environment.getProperty("local.server.port"));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 이 프로젝트가 만든 지표 줄만 추린다 (JVM 등 기본 지표 제외) */
    private String scrapeOwnMetrics() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");
        if (response.statusCode() != 200) return "";
        return Arrays.stream(response.body().split("\n"))
                .filter(line -> line.startsWith("notification_") || line.startsWith("crawl_"))
                .map(line -> "  " + line)
                .collect(Collectors.joining("\n"));
    }

    private String waitForMetric(String name, String label, double expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        String metrics;
        do {
            metrics = scrapeOwnMetrics();
            if (sample(metrics, name, label) == expected) break;
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        return metrics;
    }

    /** Prometheus 텍스트에서 이름·라벨이 맞는 줄의 값. 없으면 -1 */
    private static double sample(String metrics, String name, String label) {
        return Arrays.stream(metrics.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith(name) && line.contains(label))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElse(-1);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }
}
