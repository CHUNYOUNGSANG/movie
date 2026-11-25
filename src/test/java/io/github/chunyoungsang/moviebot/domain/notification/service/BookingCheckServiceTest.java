package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.CrawlException;
import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.domain.movie.service.MovieService;
import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.domain.notification.dto.PendingGroup;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingCheckService")
class BookingCheckServiceTest {

    @Mock private MovieService movieService;
    @Mock private NotificationStateService notificationStateService;
    @Mock private MovieCrawler cgvCrawler;

    private BookingCheckService service;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        service = new BookingCheckService(
                notificationStateService, movieService,
                List.of(cgvCrawler),
                Runnable::run,   // 크롤링을 호출 스레드에서 바로 실행 (단위 테스트는 순서/상호작용만 검증)
                meterRegistry,
                java.time.Duration.ofMinutes(8)
        );
    }

    @Nested
    @DisplayName("checkAndNotify()")
    class CheckAndNotify {

        @Test
        @DisplayName("대기 신청 없으면 크롤러를 호출하지 않는다")
        void skipsWhenNoPending() {
            given(notificationStateService.findPendingGroups()).willReturn(List.of());

            service.checkAndNotify();

            then(cgvCrawler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("크롤링 결과를 스케줄로 저장하고, 그룹과 함께 매칭 처리를 위임한다")
        void savesSchedulesAndDelegatesMatching() {
            PendingGroup group = new PendingGroup("스파이더맨", "스파이더맨", null, LocalDate.of(2026, 9, 4));
            List<ScreeningResult> results = List.of(screening("스파이더맨"));

            given(notificationStateService.findPendingGroups()).willReturn(List.of(group));
            given(cgvCrawler.crawl(eq("스파이더맨"), isNull(), any())).willReturn(results);

            service.checkAndNotify();

            then(movieService).should().saveScreenings(results);
            then(notificationStateService).should().markMatched(group, results);
        }

        @Test
        @DisplayName("크롤링 결과가 없으면 저장/매칭을 하지 않는다")
        void skipsWhenNoResults() {
            PendingGroup group = new PendingGroup("스파이더맨", "스파이더맨", null, LocalDate.of(2026, 9, 4));

            given(notificationStateService.findPendingGroups()).willReturn(List.of(group));
            given(cgvCrawler.crawl(any(), any(), any())).willReturn(List.of());

            service.checkAndNotify();

            then(movieService).shouldHaveNoInteractions();
            then(notificationStateService).should(never()).markMatched(any(PendingGroup.class), any());
        }

        @Test
        @DisplayName("크롤링이 실패하면(CrawlException) 저장/매칭을 하지 않고, 결과 없음이 아니라 오류로 기록한다")
        void recordsCrawlFailureAsError() {
            PendingGroup group = new PendingGroup("스파이더맨", "스파이더맨", null, LocalDate.of(2026, 9, 4));

            given(notificationStateService.findPendingGroups()).willReturn(List.of(group));
            given(cgvCrawler.getCinemaName()).willReturn("CGV");
            given(cgvCrawler.crawl(any(), any(), any())).willThrow(new CrawlException("http_403", "Cloudflare 차단 (테스트)"));

            service.checkAndNotify();   // 예외를 밖으로 던지지 않음

            then(movieService).shouldHaveNoInteractions();
            assertThat(meterRegistry.get("crawl.duration").tag("outcome", "error").timer().count()).isEqualTo(1);
            assertThat(meterRegistry.find("crawl.duration").tag("outcome", "empty").timer()).isNull();
        }
    }

    @Nested
    @DisplayName("주기 제한 시간")
    class CycleTimeout {

        @Test
        @DisplayName("제한 시간까지 끝나지 않은 크롤링은 기다리지 않고 취소하며, crawl.cycle.timeout 을 센다")
        void stopsWaitingAtTimeout() {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            try {
                BookingCheckService timed = new BookingCheckService(notificationStateService, movieService,
                        List.of(cgvCrawler), pool, meterRegistry, java.time.Duration.ofMillis(100));
                given(notificationStateService.findPendingGroups()).willReturn(List.of(
                        new PendingGroup("A", "A", null, LocalDate.of(2026, 9, 4)),
                        new PendingGroup("B", "B", null, LocalDate.of(2026, 9, 4))));
                given(cgvCrawler.crawl(any(), any(), any())).willAnswer(i -> {
                    release.await();   // 응답하지 않는 CGV
                    return List.of(screening("A"));
                });

                long started = System.nanoTime();
                timed.checkAndNotify();
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;

                assertThat(elapsedMs).isLessThan(1_000);
                assertThat(meterRegistry.counter("crawl.cycle.timeout").count()).isEqualTo(1);
                release.countDown();   // 늦게 끝난 결과는 버린다
                pool.shutdown();
                assertThat(pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                then(cgvCrawler).should(org.mockito.Mockito.times(1)).crawl(any(), any(), any());   // 큐에 있던 두 번째는 시작하지 않음
                then(movieService).shouldHaveNoInteractions();
                then(notificationStateService).should(never()).markMatched(any(PendingGroup.class), any());
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("종료 신호를 받은 뒤에는 새 주기를 시작하지 않는다")
        void skipsCycleAfterShutdown() {
            service.onShutdown();

            service.checkAndNotify();

            then(notificationStateService).shouldHaveNoInteractions();
            then(cgvCrawler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("제한 시간 안에 끝나면 지표를 남기지 않는다")
        void noTimeoutWhenFast() {
            given(notificationStateService.findPendingGroups()).willReturn(List.of(
                    new PendingGroup("A", "A", null, LocalDate.of(2026, 9, 4))));
            given(cgvCrawler.crawl(any(), any(), any())).willReturn(List.of());

            service.checkAndNotify();

            assertThat(meterRegistry.find("crawl.cycle.timeout").counter()).isNull();
        }
    }

    // ── helpers ───────────────────────────────────────────────

    private User user(String chatId) {
        return User.builder().userId(1L).chatId(chatId).active(true).build();
    }

    private Movie movie(String title) {
        return Movie.builder().movieId(title).title(title).build();
    }

    private Theater theater(String brand, String branchName) {
        return Theater.builder().theaterId(brand + "|" + branchName)
                .brand(brand).branchName(branchName).build();
    }

    private ScreenType screenType(String typeName) {
        return ScreenType.builder().screenTypeId(1).typeName(typeName).build();
    }

    private ScreeningResult screening(String title) {
        return new ScreeningResult("CGV", "강남", title,
                LocalDate.of(2026, 9, 4), LocalTime.of(14, 0),
                50, true, "https://cgv.co.kr");
    }
}