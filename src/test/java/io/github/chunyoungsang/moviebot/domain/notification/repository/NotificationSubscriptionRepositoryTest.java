package io.github.chunyoungsang.moviebot.domain.notification.repository;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 알림 신청 쿼리를 실제 MySQL 로 검증한다 (문자열 JPQL 조건은 컴파일러가 잡아 주지 않는다) */
@SpringBootTest(properties = {"telegram.bot.token=test-token", "movie.check.cron=-"})
@DisplayName("NotificationSubscriptionRepository 쿼리")
class NotificationSubscriptionRepositoryTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 24);   // 쿼리 인자로 넘기므로 고정 날짜여도 된다

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;

    @AfterEach
    void cleanUp() {
        subscriptionRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("findPending: 미발송 + 구독 중인 사용자 + 상영일이 오늘 이후(오늘 포함)만")
    void findPending() {
        User active = userRepository.save(User.builder().chatId("1").username("a").build());
        User stopped = userRepository.save(User.builder().chatId("2").username("b").active(false).build());
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());

        Long today = save(active, movie, TODAY, false);
        Long tomorrow = save(active, movie, TODAY.plusDays(1), false);
        save(active, movie, TODAY.minusDays(1), false);          // 어제: 제외
        save(active, movie, TODAY.plusDays(2), true);            // 이미 매칭: 제외
        save(stopped, movie, TODAY.plusDays(3), false);          // 구독 해제한 사용자: 제외

        assertThat(subscriptionRepository.findPending(TODAY))
                .extracting(NotificationSubscription::getSubscriptionId)
                .containsExactlyInAnyOrder(today, tomorrow);
    }

    private Long save(User user, Movie movie, LocalDate date, boolean triggered) {
        return subscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).targetDate(date).triggered(triggered).build()).getSubscriptionId();
    }
}
