package io.github.chunyoungsang.moviebot.global.exception;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.global.security.JwtTokenIssuer;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;

/**
 * REST API 의 오류 응답.
 * 비즈니스 오류는 상황에 맞는 상태 코드와 오류 코드로, 예상하지 못한 오류는 내부 정보 없이 500 으로 응답해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"telegram.bot.token=test-token", "movie.check.cron=-"})
@DisplayName("API 오류 응답")
class ApiErrorResponseTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;
    @MockitoSpyBean private NotificationService notificationService;

    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private Environment environment;
    @Autowired private JwtTokenIssuer tokenIssuer;

    private final HttpClient http = HttpClient.newHttpClient();
    private Long othersSubscriptionId;

    @BeforeEach
    void setUp() {
        if (userRepository.findByChatId("111").isPresent()) {
            othersSubscriptionId = subscriptionRepository.findAll().get(0).getSubscriptionId();
            return;
        }
        userRepository.save(User.builder().chatId("111").username("me").build());
        User other = userRepository.save(User.builder().chatId("222").username("other").build());
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        othersSubscriptionId = subscriptionRepository.save(NotificationSubscription.builder()
                .user(other).movie(movie).targetDate(TestDates.future()).build()).getSubscriptionId();
    }

    @Test
    @DisplayName("가입하지 않은 사용자로 신청하면 404 USER_NOT_FOUND")
    void registerWithUnknownUser() throws Exception {
        var response = send("POST", "/api/notifications", tokenOf("999"),
                "{\"movieTitle\":\"위키드\",\"targetDate\":\"" + TestDates.future() + "\"}");

        assertError(response, 404, "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("지난 날짜로 신청하면 400 TARGET_DATE_PAST, 같은 조건으로 두 번 신청하면 409 DUPLICATE_SUBSCRIPTION")
    void registerValidation() throws Exception {
        String past = "{\"movieTitle\":\"위키드\",\"targetDate\":\"" + TestDates.today().minusDays(1) + "\"}";
        assertError(send("POST", "/api/notifications", tokenOf("111"), past), 400, "TARGET_DATE_PAST");

        String body = "{\"movieTitle\":\"파묘\",\"targetDate\":\"" + TestDates.future() + "\"}";
        assertThat(send("POST", "/api/notifications", tokenOf("111"), body).statusCode()).isEqualTo(200);
        assertError(send("POST", "/api/notifications", tokenOf("111"), body), 409, "DUPLICATE_SUBSCRIPTION");
    }

    @Test
    @DisplayName("없는 알림을 취소하면 404 SUBSCRIPTION_NOT_FOUND")
    void cancelUnknownSubscription() throws Exception {
        assertError(send("DELETE", "/api/notifications/99999", tokenOf("111"), null), 404, "SUBSCRIPTION_NOT_FOUND");
    }

    @Test
    @DisplayName("남의 알림을 취소하면 403 SUBSCRIPTION_ACCESS_DENIED, 삭제되지 않는다")
    void cancelOthersSubscription() throws Exception {
        assertError(send("DELETE", "/api/notifications/" + othersSubscriptionId, tokenOf("111"), null),
                403, "SUBSCRIPTION_ACCESS_DENIED");
        assertThat(subscriptionRepository.findById(othersSubscriptionId)).isPresent();
    }

    @Test
    @DisplayName("요청 형식이 틀리면 400 INVALID_INPUT (번호가 숫자가 아님, JSON 이 깨짐)")
    void invalidRequest() throws Exception {
        assertError(send("DELETE", "/api/notifications/abc", tokenOf("111"), null), 400, "INVALID_INPUT");
        assertError(send("POST", "/api/notifications", tokenOf("111"), "{\"movieTitle\":"), 400, "INVALID_INPUT");
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 401 UNAUTHORIZED (같은 오류 형식, 실패 이유는 싣지 않음)")
    void unauthorized() throws Exception {
        assertError(send("GET", "/api/notifications", null, null), 401, "UNAUTHORIZED");

        var forged = send("GET", "/api/notifications", tokenOf("111") + "x", null);
        assertError(forged, 401, "UNAUTHORIZED");
        assertThat(forged.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).startsWith("Bearer"));
    }

    @Test
    @DisplayName("예상하지 못한 오류는 500 INTERNAL_ERROR, 내부 메시지를 응답에 싣지 않는다")
    void unexpectedErrorHidesDetails() throws Exception {
        willThrow(new IllegalArgumentException("내부 상세: jdbc:mysql://db:3306/moviebot"))
                .given(notificationService).findByChatId(anyString());

        var response = send("GET", "/api/notifications", tokenOf("111"), null);

        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("jdbc:mysql");
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        System.out.printf("[API 오류] %s %s → %d %s%n", response.request().method(),
                response.request().uri().getRawPath() + (response.request().uri().getRawQuery() == null ? "" : "?" + response.request().uri().getRawQuery()),
                response.statusCode(), response.body());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).contains("json"));
        assertThat(response.body()).contains("\"code\":\"" + code + "\"").contains("\"message\":");
    }

    private String tokenOf(String chatId) {
        return tokenIssuer.issue(chatId).accessToken();
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
