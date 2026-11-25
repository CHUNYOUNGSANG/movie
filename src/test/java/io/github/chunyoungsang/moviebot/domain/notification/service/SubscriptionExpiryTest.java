package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.repository.NotificationOutboxRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.support.TestDates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 상영일이 지난 신청의 만료 처리와 안내 (텔레그램은 RestTemplate mock) */
@SpringBootTest(properties = {"telegram.bot.token=test-token", "movie.check.cron=-", "notification.expiry.cron=-"})
@DisplayName("알림 신청 만료")
class SubscriptionExpiryTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private SubscriptionExpiryService expiryService;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private NotificationHistoryRepository historyRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("상영일이 지난 대기 중 신청만 만료하고, 구독 중인 사용자에게 안내를 한 번만 보낸다")
    void expiresPastSubscriptionsOnce() {
        User active = userRepository.save(User.builder().chatId("9101").username("a").build());
        User stopped = userRepository.save(User.builder().chatId("9102").username("b").active(false).build());
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        LocalDate yesterday = TestDates.today().minusDays(1);

        Long past = save(active, movie, yesterday, false);
        Long today = save(active, movie, TestDates.today(), false);           // 오늘 상영: 아직 아님
        Long matched = save(active, movie, yesterday.minusDays(1), true);     // 이미 알림 받음: 대상 아님
        Long stoppedPast = save(stopped, movie, yesterday, false);           // 구독 해제한 사용자: 만료만, 안내 없음

        int first = expiryService.expirePast();
        await().atMost(Duration.ofSeconds(3)).until(() -> sendCount("9101") >= 1);
        int second = expiryService.expirePast();                              // 다음 날 다시 돌아도
        sleepQuietly(500);

        System.out.printf("[신청 만료] 1회차 만료 %d건, 2회차 %d건, 안내 발송: 구독 중 %d건 / 구독 해제 %d건%n",
                first, second, sendCount("9101"), sendCount("9102"));

        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();
        assertThat(expired(past)).isTrue();
        assertThat(expired(stoppedPast)).isTrue();
        assertThat(expired(today)).isFalse();
        assertThat(expired(matched)).isFalse();
        assertThat(sendCount("9101")).isEqualTo(1);
        assertThat(sendCount("9102")).isZero();
        assertThat(sentText("9101")).contains("알림 신청이 종료됐어요").contains("위키드").contains(yesterday.toString());
    }

    private Long save(User user, Movie movie, LocalDate date, boolean triggered) {
        return subscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).targetDate(date).triggered(triggered).build()).getSubscriptionId();
    }

    private boolean expired(Long id) {
        return subscriptionRepository.findById(id).orElseThrow().isExpired();
    }

    private int sendCount(String chatId) {
        return (int) Mockito.mockingDetails(restTemplate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("postForObject")
                        && i.getRawArguments()[1] instanceof Map<?, ?> body && chatId.equals(body.get("chat_id")))
                .count();
    }

    private String sentText(String chatId) {
        return Mockito.mockingDetails(restTemplate).getInvocations().stream()
                .filter(i -> i.getRawArguments().length > 1 && i.getRawArguments()[1] instanceof Map<?, ?> body
                        && chatId.equals(body.get("chat_id")))
                .map(i -> String.valueOf(((Map<?, ?>) i.getRawArguments()[1]).get("text")))
                .findFirst().orElse("");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
