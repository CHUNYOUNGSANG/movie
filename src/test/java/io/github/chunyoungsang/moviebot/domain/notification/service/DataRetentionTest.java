package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.repository.UserRepository;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.support.TestDates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데이터 보관 정책 검증과 전후 측정.
 *
 * 약 1년 운영한 것처럼 쌓인 데이터:
 * - 상영 일정 21,000건 (상영일이 7일 넘게 지난 것 20,000 / 그 외 1,000)
 * - 신청 12,002건: 상영일이 90일 넘게 지난 끝난 신청(매칭·만료) 10,000 + 최근 끝난 신청 1,000 + 대기 1,000
 *   + 지우면 안 되는 경계 2건 (발송 중인 Outbox 가 있는 오래된 신청, 아직 만료 처리 전인 오래된 신청)
 * - 끝난 신청마다 Outbox 1건, 매칭된 신청마다 발송 이력 1건
 * 사용자 한 명(오래 쓴 사용자)이 끝난 신청 500건을 가지고 있어, 그 사용자의 목록 조회가 무엇을 읽는지도 잰다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.expiry.cron=-",
        "retention.cron=-",
        "retention.batch-size=1000"
})
@DisplayName("데이터 보관 정책")
class DataRetentionTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);
    }

    private static final int USERS = 100;
    private static final int OLD_SCHEDULES = 20_000;
    private static final int KEPT_SCHEDULES = 1_000;
    private static final int OLD_FINISHED = 10_000;
    private static final int HEAVY_USER_OLD = 500;
    private static final int RECENT_FINISHED = 1_000;
    private static final int PENDING = 1_000;
    private static final List<String> TABLES =
            List.of("schedules", "notification_subscriptions", "notification_outbox", "notification_histories");

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private DataRetentionService dataRetentionService;
    @Autowired private NotificationService notificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private final LocalDate today = TestDates.today();

    @Test
    @DisplayName("[보관 정책] 보관 기간이 지난 일정·끝난 신청(Outbox·이력 포함)만 지우고, 대기·최근·발송 중인 것은 남긴다")
    void purgesOnlyExpiredData() {
        long pendingOutboxEdge = seed();
        User heavy = userRepository.findById(1L).orElseThrow();

        Map<String, Long> before = counts();
        int heavyBefore = notificationService.findByUser(heavy).size();

        long started = System.nanoTime();
        DataRetentionService.Result result = dataRetentionService.purge();
        long purgeMs = (System.nanoTime() - started) / 1_000_000;

        Map<String, Long> after = counts();
        int heavyAfter = notificationService.findByUser(heavy).size();

        TABLES.forEach(t -> System.out.printf("[보관 정책] %-27s %,7d → %,7d건%n", t, before.get(t), after.get(t)));
        System.out.printf("[보관 정책] 오래 쓴 사용자의 신청 목록 조회가 읽는 신청 %,d → %,d건%n", heavyBefore, heavyAfter);
        System.out.printf("[보관 정책] 삭제 %,dms (일정 %,d / 신청 %,d / Outbox %,d, 배치 1,000건씩)%n",
                purgeMs, result.schedules(), result.subscriptions(), result.outbox());

        assertThat(result.schedules()).isEqualTo(OLD_SCHEDULES);
        assertThat(result.subscriptions()).isEqualTo(OLD_FINISHED);
        assertThat(result.outbox()).isEqualTo(OLD_FINISHED);
        assertThat(after.get("schedules")).isEqualTo(KEPT_SCHEDULES);
        assertThat(after.get("notification_subscriptions")).isEqualTo(RECENT_FINISHED + PENDING + 2);
        assertThat(after.get("notification_outbox")).isEqualTo(RECENT_FINISHED + 1);       // 최근 + 발송 중(경계)
        assertThat(after.get("notification_histories")).isEqualTo(RECENT_FINISHED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE outbox_id = ?", Long.class,
                pendingOutboxEdge)).as("발송 중인 Outbox 와 그 신청은 남긴다").isEqualTo(1);
        assertThat(heavyAfter).isEqualTo(3);   // 대기 중인 3건만

        assertThat(dataRetentionService.purge()).as("다시 돌려도 지울 것이 없다")
                .isEqualTo(new DataRetentionService.Result(0, 0, 0));
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        TABLES.forEach(t -> counts.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class)));
        return counts;
    }

    /** @return 경계: 발송 중(PENDING)인 오래된 신청의 outbox_id */
    private long seed() {
        List<Object[]> users = new ArrayList<>();
        for (int u = 1; u <= USERS; u++) users.add(new Object[]{(long) u, "c" + u, "u" + u});
        jdbc.batchUpdate("INSERT INTO users (user_id, chat_id, username, active) VALUES (?, ?, ?, TRUE)", users);
        List<Object[]> movies = new ArrayList<>();
        for (int m = 1; m <= 10; m++) movies.add(new Object[]{"영화" + m, "영화" + m});
        jdbc.batchUpdate("INSERT INTO movies (movie_id, title) VALUES (?, ?)", movies);

        List<Object[]> schedules = new ArrayList<>();
        for (int i = 0; i < OLD_SCHEDULES; i++) schedules.add(schedule(i, today.minusDays(8 + i % 300)));
        for (int i = 0; i < KEPT_SCHEDULES; i++) schedules.add(schedule(OLD_SCHEDULES + i, today.minusDays(7).plusDays(i % 30)));
        jdbc.batchUpdate("INSERT INTO schedules (movie_id, show_date, show_time, is_opened) VALUES (?, ?, ?, TRUE)", schedules);

        List<Object[]> subs = new ArrayList<>();
        List<Object[]> outbox = new ArrayList<>();
        List<Object[]> histories = new ArrayList<>();
        long id = 0;
        for (int i = 0; i < OLD_FINISHED; i++) {
            boolean triggered = i % 2 == 0;   // 절반은 매칭, 절반은 만료
            long user = i < HEAVY_USER_OLD ? 1 : 2 + i % (USERS - 1);
            finished(++id, user, today.minusDays(91 + i % 270), triggered, subs, outbox, histories);
        }
        for (int i = 0; i < RECENT_FINISHED; i++) finished(++id, 2 + i % (USERS - 1), today.minusDays(30), true, subs, outbox, histories);
        for (int i = 0; i < PENDING; i++) subs.add(sub(++id, i < 3 ? 1 : 2 + i % (USERS - 1), TestDates.future(), false, false));
        long pendingOutboxSub = ++id;   // 경계 1: 오래됐지만 Outbox 가 아직 발송 중
        subs.add(sub(pendingOutboxSub, 2, today.minusDays(200), true, false));
        subs.add(sub(++id, 2, today.minusDays(200), false, false));   // 경계 2: 아직 만료 처리 전

        jdbc.batchUpdate("INSERT INTO notification_subscriptions (subscription_id, user_id, movie_id, target_date, is_triggered, expired_at) VALUES (?, ?, ?, ?, ?, ?)", subs);
        jdbc.batchUpdate("INSERT INTO notification_outbox (subscription_id, chat_id, message, status, next_attempt_at, created_at, updated_at) VALUES (?, 'c', '알림', ?, ?, ?, ?)", outbox);
        jdbc.batchUpdate("INSERT INTO notification_histories (subscription_id, sent_at, status) VALUES (?, ?, 'SUCCESS')", histories);
        // 재시도 대기 중(다음 시도 1시간 뒤)으로 둔다. 다음 시도 시각이 지금이면 relay 스케줄러(10초 주기)가
        // purge 전에 발송해 SENT 로 바꿀 수 있어, 그때는 이 신청도 지워져 결과가 실행마다 달라진다 (CI 에서 실패)
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Timestamp retryLater = Timestamp.valueOf(LocalDateTime.now().plusHours(1));
        jdbc.update("INSERT INTO notification_outbox (subscription_id, chat_id, message, status, next_attempt_at, created_at, updated_at) VALUES (?, 'c', '알림', 'PENDING', ?, ?, ?)",
                pendingOutboxSub, retryLater, now, now);
        return jdbc.queryForObject("SELECT outbox_id FROM notification_outbox WHERE subscription_id = ?", Long.class, pendingOutboxSub);
    }

    private Object[] schedule(int i, LocalDate date) {
        return new Object[]{"영화" + (1 + i % 10), Date.valueOf(date), java.sql.Time.valueOf(LocalTime.of(0, 0).plusMinutes(i % 1440))};
    }

    private void finished(long id, long user, LocalDate date, boolean triggered,
                          List<Object[]> subs, List<Object[]> outbox, List<Object[]> histories) {
        subs.add(sub(id, user, date, triggered, !triggered));
        Timestamp at = Timestamp.valueOf(date.atStartOfDay());
        outbox.add(new Object[]{id, "SENT", at, at, at});   // 매칭 알림 또는 만료 안내
        if (triggered) histories.add(new Object[]{id, at});
    }

    private Object[] sub(long id, long user, LocalDate date, boolean triggered, boolean expired) {
        return new Object[]{id, user, "영화" + (1 + id % 10), Date.valueOf(date), triggered,
                expired ? Timestamp.valueOf(date.plusDays(1).atStartOfDay()) : null};
    }
}
