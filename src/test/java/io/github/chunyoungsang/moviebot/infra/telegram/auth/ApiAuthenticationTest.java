package io.github.chunyoungsang.moviebot.infra.telegram.auth;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.support.TelegramLoginFixture;
import io.github.chunyoungsang.moviebot.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 인증 재현: /api/notifications 는 텔레그램을 거치지 않고 누구나 호출할 수 있고,
 * 본인 확인을 요청에 적힌 chatId 로만 한다.
 *
 * 각 테스트는 "개선 후 기대 동작"을 검증한다. 개선 전 코드에서는 실패하는 것이 정상이며,
 * 출력([API 인증] ...)이 곧 문제의 재현 결과다.
 * - 개선 후: 텔레그램 로그인 서명을 검증한 사용자에게만 토큰을 주고, API 는 토큰의 사용자 본인 데이터만 다룬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"telegram.bot.token=" + TelegramLoginFixture.BOT_TOKEN, "movie.check.cron=-"})
@DisplayName("API 인증 재현")
class ApiAuthenticationTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final String ALICE = "5550001111";
    private static final String BOB = "5550002222";

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private Long bobSubscriptionId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userService.activate(ALICE, "alice");
        userService.activate(BOB, "bob");
        notificationService.register(new NotificationRegisterRequest(ALICE, "위키드", "CGV", "강남", "IMAX", TestDates.future()));
        bobSubscriptionId = notificationService.register(
                new NotificationRegisterRequest(BOB, "파묘", "CGV", null, null, TestDates.future().plusDays(1))).getSubscriptionId();
    }

    @Test
    @DisplayName("[익명 조회] 토큰 없이 목록을 조회하면 401, 다른 사용자의 chatId 가 노출되지 않는다")
    void anonymousCannotListAll() throws Exception {
        var res = send("GET", "/api/notifications", null, null);
        int exposed = countChatIds(res.body());
        System.out.printf("[API 인증] 토큰 없이 전체 조회 → %d, 노출된 다른 사용자 chatId %d개%n", res.statusCode(), exposed);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(exposed).isZero();
    }

    @Test
    @DisplayName("[익명 취소] 토큰 없이 남의 chatId 로 취소하면 401, 남의 알림은 그대로다")
    void anonymousCannotCancelOthers() throws Exception {
        var res = send("DELETE", "/api/notifications/" + bobSubscriptionId + "?chatId=" + BOB, null, null);
        boolean deleted = subscriptionRepository.findById(bobSubscriptionId).isEmpty();
        System.out.printf("[API 인증] 토큰 없이 남의 알림 취소 → %d, 삭제됨=%s%n", res.statusCode(), deleted);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("[익명 신청] 토큰 없이 남의 chatId 로 신청하면 401, 남에게 알림이 등록되지 않는다")
    void anonymousCannotRegisterForOthers() throws Exception {
        long before = bobSubscriptions();
        var res = send("POST", "/api/notifications", null,
                "{\"chatId\":\"" + BOB + "\",\"movieTitle\":\"스팸\",\"targetDate\":\"" + TestDates.future() + "\"}");
        long added = bobSubscriptions() - before;
        System.out.printf("[API 인증] 토큰 없이 남의 이름으로 신청 → %d, 남에게 등록된 알림 %d건%n", res.statusCode(), added);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(added).isZero();
    }

    @Test
    @DisplayName("[로그인 후 남의 것] 로그인한 alice 가 bob 의 chatId 를 적어도 bob 의 알림은 취소되지 않는다")
    void loggedInUserCannotCancelOthers() throws Exception {
        String token = login(ALICE);
        var res = send("DELETE", "/api/notifications/" + bobSubscriptionId + "?chatId=" + BOB, token, null);
        boolean deleted = subscriptionRepository.findById(bobSubscriptionId).isEmpty();
        System.out.printf("[API 인증] alice 로그인 후 bob 의 알림 취소 → %d, 삭제됨=%s%n", res.statusCode(), deleted);

        assertThat(res.statusCode()).isBetween(400, 499);
        assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("[본인 조회] 로그인하면 내 알림만 보이고, 응답에 chatId 가 없다")
    void loggedInUserSeesOnlyOwn() throws Exception {
        var res = send("GET", "/api/notifications", login(ALICE), null);
        System.out.printf("[API 인증] alice 로그인 후 조회 → %d, 본문=%s%n", res.statusCode(), res.body());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("위키드").doesNotContain("파묘");
        assertThat(countChatIds(res.body())).isZero();
    }

    @Test
    @DisplayName("[로그인 위조] 서명이 틀리거나 오래된 로그인 정보로는 토큰을 받을 수 없다")
    void forgedOrExpiredLoginIsRejected() throws Exception {
        String forged = TelegramLoginFixture.signedJson(BOB, Instant.now()).replaceFirst("\"hash\":\"[0-9a-f]{4}", "\"hash\":\"0000");
        String expired = TelegramLoginFixture.signedJson(BOB, Instant.now().minusSeconds(3 * 24 * 3600));

        int forgedStatus = send("POST", "/api/auth/telegram", null, forged).statusCode();
        int expiredStatus = send("POST", "/api/auth/telegram", null, expired).statusCode();
        System.out.printf("[API 인증] 위조 서명 로그인 → %d, 3일 지난 로그인 → %d%n", forgedStatus, expiredStatus);

        assertThat(forgedStatus).isEqualTo(401);
        assertThat(expiredStatus).isEqualTo(401);
    }

    // ── helpers ───────────────────────────────────────────────

    private String login(String chatId) throws Exception {
        var res = send("POST", "/api/auth/telegram", null, TelegramLoginFixture.signedJson(chatId, Instant.now()));
        Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
        return m.find() ? m.group(1) : "login-failed-" + res.statusCode();
    }

    private long bobSubscriptions() {
        return subscriptionRepository.findAll().stream()
                .filter(s -> userRepository.findById(s.getUser().getUserId()).orElseThrow().getChatId().equals(BOB))
                .count();
    }

    private static int countChatIds(String body) {
        Matcher m = Pattern.compile("\"chatId\"").matcher(body);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        String port = environment.getProperty("local.server.port");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) builder.header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
