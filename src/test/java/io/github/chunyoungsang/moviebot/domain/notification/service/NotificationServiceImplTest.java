package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;

import io.github.chunyoungsang.moviebot.global.exception.BusinessException;
import io.github.chunyoungsang.moviebot.domain.notification.exception.NotificationErrorCode;
import io.github.chunyoungsang.moviebot.domain.user.exception.UserErrorCode;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.CrawlException;
import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.domain.movie.service.MovieService;
import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
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
@DisplayName("NotificationServiceImpl")
class NotificationServiceImplTest {

    @Mock private NotificationSubscriptionRepository notificationSubscriptionRepository;
    @Mock private MovieService movieService;
    @Mock private UserService userService;
    @Mock private io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService outboxService;

    private NotificationServiceImpl service;

    /** 2026-12-24 10:00 (서울) 로 고정 */
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            java.time.Instant.parse("2026-12-24T01:00:00Z"), java.time.ZoneId.of("Asia/Seoul"));
    private static final LocalDate TODAY = LocalDate.of(2026, 12, 24);

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(
                notificationSubscriptionRepository, movieService, userService, outboxService, CLOCK);
    }

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("존재하지 않는 chatId 이면 예외를 던진다")
        void throwsWhenUserNotFound() {
            given(userService.getByChatIdOrThrow("unknown"))
                    .willThrow(new BusinessException(UserErrorCode.USER_NOT_FOUND));

            assertThatThrownBy(() -> service.register(new NotificationRegisterRequest(
                    "unknown", "스파이더맨", "CGV", "영등포", "IMAX", TODAY
            ))).isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("정상 요청 시 NotificationSubscription 을 저장한다")
        void savesSubscriptionSuccessfully() {
            given(userService.getByChatIdOrThrow("111")).willReturn(user("111"));
            given(movieService.findOrCreateMovie("스파이더맨")).willReturn(movie("스파이더맨"));
            given(movieService.findOrCreateTheater("CGV", "영등포"))
                    .willReturn(theater("CGV", "영등포"));
            given(movieService.findOrCreateScreenType("IMAX"))
                    .willReturn(screenType("IMAX"));
            given(notificationSubscriptionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.register(new NotificationRegisterRequest(
                    "111", "스파이더맨", "CGV", "영등포", "IMAX", TODAY));

            then(notificationSubscriptionRepository).should().save(any());
        }

        @Test
        @DisplayName("오늘 날짜는 받고, 어제 날짜는 TARGET_DATE_PAST (서울 기준 날짜 경계)")
        void rejectsPastDate() {
            assertThatThrownBy(() -> service.register(new NotificationRegisterRequest(
                    "111", "스파이더맨", "CGV", null, null, TODAY.minusDays(1))))
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(NotificationErrorCode.TARGET_DATE_PAST);
            then(userService).shouldHaveNoInteractions();   // 사용자 조회 전에 거른다
        }

        @Test
        @DisplayName("제목이 비었거나 100자를 넘거나 날짜가 없으면 INVALID_SUBSCRIPTION_REQUEST")
        void rejectsInvalidInput() {
            for (NotificationRegisterRequest bad : java.util.List.of(
                    new NotificationRegisterRequest("111", " ", "CGV", null, null, TODAY),
                    new NotificationRegisterRequest("111", null, "CGV", null, null, TODAY),
                    new NotificationRegisterRequest("111", "가".repeat(101), "CGV", null, null, TODAY),
                    new NotificationRegisterRequest("111", "스파이더맨", "CGV", null, null, null))) {
                assertThatThrownBy(() -> service.register(bad))
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(NotificationErrorCode.INVALID_SUBSCRIPTION_REQUEST);
            }
            then(notificationSubscriptionRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("제목 앞뒤 공백은 지우고 저장한다 (같은 영화가 공백 때문에 따로 생기지 않게)")
        void stripsTitle() {
            given(userService.getByChatIdOrThrow("111")).willReturn(user("111"));
            given(movieService.findOrCreateMovie("스파이더맨")).willReturn(movie("스파이더맨"));
            given(notificationSubscriptionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.register(new NotificationRegisterRequest("111", "  스파이더맨 ", "CGV", null, null, TODAY));

            then(movieService).should().findOrCreateMovie("스파이더맨");
        }

        @Test
        @DisplayName("같은 조건의 대기 중인 신청이 있으면 DUPLICATE_SUBSCRIPTION, 저장하지 않는다")
        void rejectsDuplicate() {
            given(userService.getByChatIdOrThrow("111")).willReturn(user("111"));
            given(movieService.findOrCreateMovie("스파이더맨")).willReturn(movie("스파이더맨"));
            given(notificationSubscriptionRepository.existsPendingDuplicate(any(), any(), any(), any(), any())).willReturn(true);

            assertThatThrownBy(() -> service.register(new NotificationRegisterRequest(
                    "111", "스파이더맨", "CGV", null, null, TODAY)))
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(NotificationErrorCode.DUPLICATE_SUBSCRIPTION);
            then(notificationSubscriptionRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("존재하지 않는 구독이면 예외를 던진다")
        void throwsWhenNotFound() {
            given(notificationSubscriptionRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(1L, "123"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(NotificationErrorCode.SUBSCRIPTION_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 소유가 아닌 구독을 취소하려 하면 예외를 던지고 삭제하지 않는다")
        void throwsWhenNotOwner() {
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("owner")).build();
            given(notificationSubscriptionRepository.findById(1L)).willReturn(Optional.of(sub));

            assertThatThrownBy(() -> service.cancel(1L, "다른사람"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(NotificationErrorCode.SUBSCRIPTION_ACCESS_DENIED);

            then(notificationSubscriptionRepository).should(never()).delete(any());
        }

        @Test
        @DisplayName("본인 소유 구독이면 정상적으로 취소한다")
        void deletesWhenOwner() {
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("123")).build();
            given(notificationSubscriptionRepository.findById(1L)).willReturn(Optional.of(sub));

            service.cancel(1L, "123");

            then(notificationSubscriptionRepository).should().delete(sub);
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