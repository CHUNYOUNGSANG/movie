package io.github.chunyoungsang.moviebot.domain.notification.outbox.repository;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.repository.MovieRepository;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.NotificationOutbox;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.OutboxStatus;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 쿼리를 실제 MySQL 로 검증한다.
 * JPQL 의 상태 조건(PENDING, SENDING, SENT)이 의도대로 걸리는지 — 문자열 쿼리라 컴파일러가 잡아 주지 않는다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.outbox.relay-delay-ms=600000",   // relay 가 테스트 데이터를 건드리지 않도록
        "monitoring.outbox.refresh-ms=600000"
})
@DisplayName("NotificationOutboxRepository 쿼리")
class NotificationOutboxRepositoryTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private NotificationOutboxRepository outboxRepository;
    @Autowired private NotificationSubscriptionRepository subscriptionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private TransactionTemplate tx;

    private static final LocalDateTime NOW = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    private NotificationSubscription sub;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        if (sub == null) {
            User user = userRepository.findByChatId("111")
                    .orElseGet(() -> userRepository.save(User.builder().chatId("111").username("u").build()));
            Movie movie = movieRepository.findById("위키드")
                    .orElseGet(() -> movieRepository.save(Movie.builder().movieId("위키드").title("위키드").build()));
            sub = subscriptionRepository.save(NotificationSubscription.builder()
                    .user(user).movie(movie).targetDate(NOW.toLocalDate()).build());
        }
    }

    @Test
    @DisplayName("claim: 발송 시각이 된 PENDING 만 SENDING 으로 선점한다 (두 번째 선점은 0)")
    void claim() {
        Long due = save(OutboxStatus.PENDING, NOW.minusSeconds(1), null);
        Long notYet = save(OutboxStatus.PENDING, NOW.plusMinutes(1), null);
        Long sent = save(OutboxStatus.SENT, NOW.minusSeconds(1), null);

        assertThat(claim(due)).isEqualTo(1);
        assertThat(claim(due)).isZero();
        assertThat(claim(notYet)).isZero();
        assertThat(claim(sent)).isZero();
        assertThat(status(due)).isEqualTo(OutboxStatus.SENDING);
    }

    @Test
    @DisplayName("findDueIds: 발송 시각이 된 PENDING 만, 오래된 것부터")
    void findDueIds() {
        Long later = save(OutboxStatus.PENDING, NOW.minusSeconds(1), null);
        Long earlier = save(OutboxStatus.PENDING, NOW.minusMinutes(1), null);
        save(OutboxStatus.PENDING, NOW.plusMinutes(1), null);
        save(OutboxStatus.SENDING, NOW.minusMinutes(5), NOW);
        save(OutboxStatus.DEAD, NOW.minusMinutes(5), null);

        assertThat(outboxRepository.findDueIds(NOW, PageRequest.of(0, 10))).containsExactly(earlier, later);
    }

    @Test
    @DisplayName("releaseStale: 기준 시각보다 오래 SENDING 에 멈춘 것만 PENDING 으로 되돌린다")
    void releaseStale() {
        Long stale = save(OutboxStatus.SENDING, NOW, NOW.minusMinutes(10));
        Long active = save(OutboxStatus.SENDING, NOW, NOW.minusSeconds(10));
        Long pending = save(OutboxStatus.PENDING, NOW, null);

        int released = tx.execute(s -> outboxRepository.releaseStale(NOW.minusMinutes(5)));

        assertThat(released).isEqualTo(1);
        assertThat(status(stale)).isEqualTo(OutboxStatus.PENDING);
        assertThat(status(active)).isEqualTo(OutboxStatus.SENDING);
        assertThat(status(pending)).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("countUnsentByStatus: SENT 를 뺀 상태별 건수")
    void countUnsentByStatus() {
        save(OutboxStatus.PENDING, NOW, null);
        save(OutboxStatus.PENDING, NOW, null);
        save(OutboxStatus.SENDING, NOW, NOW);
        save(OutboxStatus.DEAD, NOW, null);
        save(OutboxStatus.SENT, NOW, null);

        Map<OutboxStatus, Long> counts = outboxRepository.countUnsentByStatus().stream()
                .collect(Collectors.toMap(NotificationOutboxRepository.StatusCountView::getStatus,
                        NotificationOutboxRepository.StatusCountView::getCount));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                OutboxStatus.PENDING, 2L, OutboxStatus.SENDING, 1L, OutboxStatus.DEAD, 1L));
    }

    @Test
    @DisplayName("findOldestPendingCreatedAt: PENDING 이 없으면 null, 있으면 값이 있다")
    void oldestPending() {
        save(OutboxStatus.SENT, NOW, null);
        assertThat(outboxRepository.findOldestPendingCreatedAt()).isNull();

        save(OutboxStatus.PENDING, NOW, null);
        assertThat(outboxRepository.findOldestPendingCreatedAt()).isNotNull();
    }

    private Long save(OutboxStatus status, LocalDateTime nextAttemptAt, LocalDateTime lockedAt) {
        return outboxRepository.save(NotificationOutbox.builder()
                .subscription(sub).chatId("111").message("본문")
                .status(status).nextAttemptAt(nextAttemptAt).lockedAt(lockedAt)
                .build()).getOutboxId();
    }

    private int claim(Long id) {
        return tx.execute(s -> outboxRepository.claim(id, NOW));
    }

    private OutboxStatus status(Long id) {
        return outboxRepository.findById(id).orElseThrow().getStatus();
    }
}
