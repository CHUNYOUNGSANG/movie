package io.github.chunyoungsang.moviebot.domain.notification.repository;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.movie.repository.TheaterRepository;
import io.github.chunyoungsang.moviebot.domain.notification.dto.PendingGroup;
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
    @Autowired private TheaterRepository theaterRepository;

    @AfterEach
    void cleanUp() {
        subscriptionRepository.deleteAllInBatch();
        theaterRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("findPendingGroups: 미발송 + 구독 중 + 상영일이 오늘 이후(오늘 포함)를 영화|지점|날짜로 한 번씩")
    void findPendingGroups() {
        User active = userRepository.save(User.builder().chatId("1").username("a").build());
        User active2 = userRepository.save(User.builder().chatId("3").username("c").build());
        User stopped = userRepository.save(User.builder().chatId("2").username("b").active(false).build());
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        Theater gangnam = theaterRepository.save(Theater.builder().theaterId("CGV|강남").brand("CGV").branchName("강남").build());

        save(active, movie, TODAY, false);
        save(active2, movie, TODAY, false);                      // 같은 조건의 다른 사용자: 같은 그룹
        save(active, movie, gangnam, TODAY, false);              // 지점 지정: 다른 그룹
        save(active, movie, TODAY.plusDays(1), false);
        save(active, movie, TODAY.minusDays(1), false);          // 어제: 제외
        save(active, movie, TODAY.plusDays(2), true);            // 이미 매칭: 제외
        save(stopped, movie, TODAY.plusDays(3), false);          // 구독 해제한 사용자: 제외

        assertThat(subscriptionRepository.findPendingGroups(TODAY)).containsExactlyInAnyOrder(
                new PendingGroup("위키드", "위키드", null, TODAY),
                new PendingGroup("위키드", "위키드", "강남", TODAY),
                new PendingGroup("위키드", "위키드", null, TODAY.plusDays(1)));
    }

    @Test
    @DisplayName("findPendingIdsInGroup: 지점이 null 이면 전체 지점 신청만, 지정하면 그 지점 신청만. 매칭됨·구독 해제 제외")
    void findPendingIdsInGroup() {
        User active = userRepository.save(User.builder().chatId("1").username("a").build());
        User stopped = userRepository.save(User.builder().chatId("2").username("b").active(false).build());
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        Theater gangnam = theaterRepository.save(Theater.builder().theaterId("CGV|강남").brand("CGV").branchName("강남").build());

        Long all = save(active, movie, TODAY, false);
        Long inGangnam = save(active, movie, gangnam, TODAY, false);
        save(active, movie, TODAY.plusDays(1), false);           // 다른 날짜
        save(active, movie, gangnam, TODAY, true);               // 이미 매칭
        save(stopped, movie, TODAY, false);                      // 구독 해제

        assertThat(subscriptionRepository.findPendingIdsInGroup("위키드", null, TODAY)).containsExactly(all);
        assertThat(subscriptionRepository.findPendingIdsInGroup("위키드", "강남", TODAY)).containsExactly(inGangnam);
    }

    @Test
    @DisplayName("existsPendingDuplicate: 지점·상영관이 null(전체)이면 null 끼리, 지정했으면 같은 값끼리 같은 조건. 매칭된 신청은 제외")
    void existsPendingDuplicate() {
        User user = userRepository.save(User.builder().chatId("1").username("a").build());
        User other = userRepository.save(User.builder().chatId("2").username("b").build());
        Movie movie = movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build());
        Theater gangnam = theaterRepository.save(Theater.builder().theaterId("CGV|강남").brand("CGV").branchName("강남").build());
        Theater hongdae = theaterRepository.save(Theater.builder().theaterId("CGV|홍대").brand("CGV").branchName("홍대").build());
        save(user, movie, TODAY, false);                                      // 전체 지점
        subscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).theater(gangnam).targetDate(TODAY).build());   // 강남
        subscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).theater(hongdae).targetDate(TODAY.plusDays(1)).triggered(true).build());   // 홍대, 매칭됨

        assertThat(subscriptionRepository.existsPendingDuplicate(user, movie, null, null, TODAY)).isTrue();
        assertThat(subscriptionRepository.existsPendingDuplicate(user, movie, gangnam, null, TODAY)).isTrue();
        assertThat(subscriptionRepository.existsPendingDuplicate(user, movie, hongdae, null, TODAY)).isFalse();         // 다른 지점
        assertThat(subscriptionRepository.existsPendingDuplicate(user, movie, null, null, TODAY.plusDays(5))).isFalse(); // 다른 날짜
        assertThat(subscriptionRepository.existsPendingDuplicate(other, movie, null, null, TODAY)).isFalse();          // 다른 사용자
        assertThat(subscriptionRepository.existsPendingDuplicate(user, movie, hongdae, null, TODAY.plusDays(1))).isFalse(); // 매칭됨
    }

    private Long save(User user, Movie movie, LocalDate date, boolean triggered) {
        return save(user, movie, null, date, triggered);
    }

    private Long save(User user, Movie movie, Theater theater, LocalDate date, boolean triggered) {
        return subscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).theater(theater).targetDate(date).triggered(triggered).build()).getSubscriptionId();
    }
}
