package domain.notification.service;

import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.service.MovieService;
import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationSubscription;
import domain.notification.repository.NotificationSubscriptionRepository;
import domain.user.entity.User;
import domain.user.service.UserService;
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
    @Mock private NotificationStateService notificationStateService;
    @Mock private domain.notification.outbox.NotificationOutboxService outboxService;
    @Mock private MovieCrawler cgvCrawler;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(
                notificationSubscriptionRepository,
                movieService, userService, notificationStateService, outboxService,
                List.of(cgvCrawler)
        );
    }

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("존재하지 않는 chatId 이면 예외를 던진다")
        void throwsWhenUserNotFound() {
            given(userService.getByChatIdOrThrow("unknown"))
                    .willThrow(new IllegalArgumentException("등록된 사용자가 없습니다. /start 를 먼저 입력하세요."));

            assertThatThrownBy(() -> service.register(new NotificationRegisterRequest(
                    "unknown", "스파이더맨", "CGV", "영등포", "IMAX", LocalDate.now()
            ))).isInstanceOf(IllegalArgumentException.class);
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
                    "111", "스파이더맨", "CGV", "영등포", "IMAX", LocalDate.now()));

            then(notificationSubscriptionRepository).should().save(any());
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
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("본인 소유가 아닌 구독을 취소하려 하면 예외를 던지고 삭제하지 않는다")
        void throwsWhenNotOwner() {
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("owner")).build();
            given(notificationSubscriptionRepository.findById(1L)).willReturn(Optional.of(sub));

            assertThatThrownBy(() -> service.cancel(1L, "다른사람"))
                    .isInstanceOf(SecurityException.class);

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