package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;
import io.github.chunyoungsang.moviebot.support.TestDates;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 알림 신청이 많을 때 한 주기의 비용 재현.
 *
 * 데이터: 신청 50,000건 = 대기 중 5,000건(영화 50편 × 100명) + 이미 매칭됐거나 상영일이 지난 45,000건.
 * 예매가 아직 안 열린 평소 주기(크롤러가 빈 결과)를 돌려
 * - 한 주기에 메모리로 읽어 들이는 엔티티 수 (Hibernate 통계)
 * - 크롤링 대상 조건(미발송 + 구독 중 + 상영일 이후)의 실행 계획 (MySQL EXPLAIN)
 * 을 잰다. 개선 전 코드에서는 실패하는 것이 정상이며, 출력([대량 처리] ...)이 곧 재현 결과다.
 */
@SpringBootTest(properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-",
        "notification.expiry.cron=-",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DisplayName("대량 신청 처리 재현")
class PendingScaleTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    private static final int MOVIES = 50;
    private static final int USERS = 1_000;
    private static final int PENDING_PER_MOVIE = 100;   // 대기 5,000
    private static final int FINISHED = 45_000;         // 매칭됨 / 상영일 지남

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private BookingCheckService bookingCheckService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory emf;

    @Test
    @DisplayName("[대량 처리] 예매가 안 열린 평소 주기에 신청 엔티티를 전부 읽지 않고, 크롤링 대상 조회는 인덱스를 탄다")
    void pendingCycleIsCheap() {
        seed();
        given(crawler.getCinemaName()).willReturn("CGV");
        given(crawler.crawl(anyString(), any(), any())).willReturn(List.of());   // 아직 예매 안 열림

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        long started = System.nanoTime();
        bookingCheckService.checkAndNotify();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        long loaded = stats.getEntityLoadCount();
        int crawled = (int) Mockito.mockingDetails(crawler).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("crawl")).count();
        Map<String, Object> plan = explainPending();
        System.out.printf("[대량 처리] 신청 %,d건(대기 %,d) → 크롤링 %d회, 메모리로 읽은 엔티티 %,d개, 주기 %,dms%n",
                MOVIES * PENDING_PER_MOVIE + FINISHED, MOVIES * PENDING_PER_MOVIE, crawled, loaded, elapsedMs);
        System.out.printf("[대량 처리] 크롤링 대상 조회 실행 계획 → 신청 테이블 type=%s, key=%s / 조인 순서 %s%n",
                plan.get("type"), plan.get("key"), plan.get("order"));

        assertThat(crawled).isEqualTo(MOVIES);                      // 같은 조건은 한 번만 크롤링 (기존 동작 유지)
        assertThat(loaded).as("평소 주기에는 그룹(영화·지점·날짜)만 읽는다").isLessThan(MOVIES * 2L);
        assertThat(plan.get("key")).as("크롤링 대상 조건(미발송 + 상영일)이 전용 인덱스를 탄다").isEqualTo("idx_subscriptions_pending");
    }

    /** 크롤링 대상 조건 그대로의 SQL 실행 계획 (조회 방식이 바뀌어도 같은 조건으로 비교) */
    private Map<String, Object> explainPending() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                EXPLAIN SELECT s.subscription_id FROM notification_subscriptions s
                JOIN users u ON u.user_id = s.user_id
                WHERE s.is_triggered = FALSE AND u.active = TRUE AND s.target_date >= ?
                """, Date.valueOf(TestDates.today()));
        Map<String, Object> plan = new java.util.HashMap<>(rows.stream()
                .filter(row -> "s".equals(row.get("table"))).findFirst().orElseThrow());
        plan.put("order", rows.stream()   // 먼저 읽는 테이블부터: users(type=ALL)가 앞이면 사용자 전체를 훑고 사용자별 신청을 모두 본다
                .map(row -> row.get("table") + "(" + row.get("type") + ", 행 " + row.get("rows") + ")").toList());
        return plan;
    }

    private void seed() {
        List<Object[]> users = new ArrayList<>();
        for (int u = 1; u <= USERS; u++) users.add(new Object[]{(long) u, "c" + u, "u" + u});
        jdbc.batchUpdate("INSERT INTO users (user_id, chat_id, username, active) VALUES (?, ?, ?, TRUE)", users);

        List<Object[]> movies = new ArrayList<>();
        for (int m = 1; m <= MOVIES; m++) movies.add(new Object[]{"영화" + m, "영화" + m});
        jdbc.batchUpdate("INSERT INTO movies (movie_id, title) VALUES (?, ?)", movies);

        LocalDate future = TestDates.future();
        LocalDate past = TestDates.today().minusDays(30);
        List<Object[]> subs = new ArrayList<>();
        for (int m = 1; m <= MOVIES; m++) {
            for (int i = 0; i < PENDING_PER_MOVIE; i++) {
                subs.add(new Object[]{(long) (1 + (m * PENDING_PER_MOVIE + i) % USERS), "영화" + m, Date.valueOf(future), false});
            }
        }
        for (int i = 0; i < FINISHED; i++) {
            boolean triggered = i % 2 == 0;   // 절반은 매칭됨, 절반은 상영일 지남
            subs.add(new Object[]{(long) (1 + i % USERS), "영화" + (1 + i % MOVIES),
                    Date.valueOf(triggered ? future : past), triggered});
        }
        jdbc.batchUpdate("INSERT INTO notification_subscriptions (user_id, movie_id, target_date, is_triggered) VALUES (?, ?, ?, ?)", subs);
        jdbc.execute("ANALYZE TABLE notification_subscriptions");   // 실행 계획이 실제 분포를 보도록 통계 갱신
    }
}
