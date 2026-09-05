package domain.notification.service;

import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.service.MovieService;
import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationSubscription;
import domain.notification.repository.NotificationHistoryRepository;
import domain.notification.repository.NotificationSubscriptionRepository;
import domain.telegram.service.MessageSender;
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
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceImpl")
class NotificationServiceImplTest {

    @Mock private NotificationSubscriptionRepository notificationSubscriptionRepository;
    @Mock private NotificationHistoryRepository notificationHistoryRepository;
    @Mock private MovieService movieService;
    @Mock private UserService userService;
    @Mock private MessageSender messageSender;   // 구체 클래스 아닌 인터페이스로 Mock
    @Mock private MovieCrawler cgvCrawler;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(
                notificationSubscriptionRepository, notificationHistoryRepository,
                movieService, userService, messageSender,
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
            given(notificationSubscriptionRepository.findAllByTriggeredFalse()).willReturn(List.of());

            service.checkAndNotify();

            then(cgvCrawler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("매칭 상영 발견 시 MessageSender.sendAlerts() 를 호출하고 triggered=true 로 변경한다")
        void notifiesOnMatch() {
            Movie movie = movie("스파이더맨");
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("123")).movie(movie)
                    .targetDate(LocalDate.of(2026, 9, 4)).build();

            given(notificationSubscriptionRepository.findAllByTriggeredFalse()).willReturn(List.of(sub));
            given(cgvCrawler.crawl(eq("스파이더맨"), isNull(), any()))
                    .willReturn(List.of(screening("스파이더맨")));
            given(movieService.findOrCreateMovie(any())).willReturn(movie);
            given(movieService.findOrCreateTheaterFromResult(any())).willReturn(theater("CGV", "강남"));
            given(movieService.findOrCreateScreenType(any())).willReturn(screenType("일반"));
            given(notificationHistoryRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.checkAndNotify();

            then(messageSender).should(times(1)).sendAlerts(eq("123"), any());
            assert sub.isTriggered();
        }

        @Test
        @DisplayName("지점 필터 불일치 시 알림을 보내지 않는다")
        void skipsWhenTheaterNotMatched() {
            Movie movie = movie("스파이더맨");
            NotificationSubscription sub = NotificationSubscription.builder()
                    .subscriptionId(1L).user(user("123")).movie(movie)
                    .theater(theater("CGV", "홍대"))   // 홍대 필터
                    .targetDate(LocalDate.of(2026, 9, 4)).build();

            given(notificationSubscriptionRepository.findAllByTriggeredFalse()).willReturn(List.of(sub));
            given(cgvCrawler.crawl(eq("스파이더맨"), eq("홍대"), any()))
                    .willReturn(List.of(screening("스파이더맨"))); // 강남 결과
            given(movieService.findOrCreateMovie(any())).willReturn(movie);
            given(movieService.findOrCreateTheaterFromResult(any())).willReturn(theater("CGV", "강남"));
            given(movieService.findOrCreateScreenType(any())).willReturn(screenType("일반"));

            service.checkAndNotify();

            then(messageSender).shouldHaveNoInteractions();
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