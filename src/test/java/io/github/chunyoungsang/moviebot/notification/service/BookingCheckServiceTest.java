package io.github.chunyoungsang.moviebot.notification.service;

import io.github.chunyoungsang.moviebot.movie.crawler.CrawlException;
import io.github.chunyoungsang.moviebot.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.movie.service.MovieService;
import io.github.chunyoungsang.moviebot.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.user.entity.User;
import io.github.chunyoungsang.moviebot.user.service.UserService;
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
                meterRegistry
        );
    }

    @Nested
    @DisplayName("checkAndNotify()")
    class CheckAndNotify {

        @Test
        @DisplayName("대기 신청 없으면 크롤러를 호출하지 않는다")
        void skipsWhenNoPending() {
            given(notificationStateService.findPending()).willReturn(List.of());

            service.checkAndNotify();

            then(cgvCrawler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("크롤링 결과를 스케줄로 저장하고, 그룹의 구독 ID 와 함께 매칭 처리를 위임한다")
        void savesSchedulesAndDelegatesMatching() {
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("123")).movie(movie("스파이더맨"))
                    .targetDate(LocalDate.of(2026, 9, 4)).build();
            List<ScreeningResult> results = List.of(screening("스파이더맨"));

            given(notificationStateService.findPending()).willReturn(List.of(sub));
            given(cgvCrawler.crawl(eq("스파이더맨"), isNull(), any())).willReturn(results);

            service.checkAndNotify();

            then(movieService).should().saveScreenings(results);
            then(notificationStateService).should().markMatched(List.of(1L), results);
        }

        @Test
        @DisplayName("크롤링 결과가 없으면 저장/매칭을 하지 않는다")
        void skipsWhenNoResults() {
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("123")).movie(movie("스파이더맨"))
                    .targetDate(LocalDate.of(2026, 9, 4)).build();

            given(notificationStateService.findPending()).willReturn(List.of(sub));
            given(cgvCrawler.crawl(any(), any(), any())).willReturn(List.of());

            service.checkAndNotify();

            then(movieService).shouldHaveNoInteractions();
            then(notificationStateService).should(never()).markMatched(any(), any());
        }

        @Test
        @DisplayName("크롤링이 실패하면(CrawlException) 저장/매칭을 하지 않고, 결과 없음이 아니라 오류로 기록한다")
        void recordsCrawlFailureAsError() {
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("123")).movie(movie("스파이더맨"))
                    .targetDate(LocalDate.of(2026, 9, 4)).build();

            given(notificationStateService.findPending()).willReturn(List.of(sub));
            given(cgvCrawler.getCinemaName()).willReturn("CGV");
            given(cgvCrawler.crawl(any(), any(), any())).willThrow(new CrawlException("http_403", "Cloudflare 차단 (테스트)"));

            service.checkAndNotify();   // 예외를 밖으로 던지지 않음

            then(movieService).shouldHaveNoInteractions();
            assertThat(meterRegistry.get("crawl.duration").tag("outcome", "error").timer().count()).isEqualTo(1);
            assertThat(meterRegistry.find("crawl.duration").tag("outcome", "empty").timer()).isNull();
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