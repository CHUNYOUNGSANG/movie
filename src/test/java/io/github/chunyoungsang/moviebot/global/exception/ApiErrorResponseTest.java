package io.github.chunyoungsang.moviebot.global.exception;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
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
                .user(other).movie(movie).targetDate(LocalDate.of(2026, 12, 24)).build()).getSubscriptionId();
    }

    @Test
    @DisplayName("가입하지 않은 사용자로 신청하면 404 USER_NOT_FOUND")
    void registerWithUnknownUser() throws Exception {
        var response = send("POST", "/api/notifications",
                "{\"chatId\":\"999\",\"movieTitle\":\"위키드\",\"targetDate\":\"2026-12-24\"}");

        assertError(response, 404, "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("없는 알림을 취소하면 404 SUBSCRIPTION_NOT_FOUND")
    void cancelUnknownSubscription() throws Exception {
        assertError(send("DELETE", "/api/notifications/99999?chatId=111", null), 404, "SUBSCRIPTION_NOT_FOUND");
    }

    @Test
    @DisplayName("남의 알림을 취소하면 403 SUBSCRIPTION_ACCESS_DENIED, 삭제되지 않는다")
    void cancelOthersSubscription() throws Exception {
        assertError(send("DELETE", "/api/notifications/" + othersSubscriptionId + "?chatId=111", null),
                403, "SUBSCRIPTION_ACCESS_DENIED");
        assertThat(subscriptionRepository.findById(othersSubscriptionId)).isPresent();
    }

    @Test
    @DisplayName("요청 형식이 틀리면 400 INVALID_INPUT (번호가 숫자가 아님, 필수 파라미터 누락)")
    void invalidRequest() throws Exception {
        assertError(send("DELETE", "/api/notifications/abc?chatId=111", null), 400, "INVALID_INPUT");
        assertError(send("DELETE", "/api/notifications/1", null), 400, "INVALID_INPUT");
    }

    @Test
    @DisplayName("예상하지 못한 오류는 500 INTERNAL_ERROR, 내부 메시지를 응답에 싣지 않는다")
    void unexpectedErrorHidesDetails() throws Exception {
        willThrow(new IllegalArgumentException("내부 상세: jdbc:mysql://db:3306/moviebot"))
                .given(notificationService).findAll();

        var response = send("GET", "/api/notifications", null);

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

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        String port = environment.getProperty("local.server.port");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) builder.header("Content-Type", "application/json");
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
