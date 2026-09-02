package domain.notification.service;

import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScheduleRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationRequest;
import domain.notification.repository.NotificationHistoryRepository;
import domain.notification.repository.NotificationRequestRepository;
import domain.telegram.service.MessageSender;
import domain.user.entity.User;
import domain.user.repository.UserRepository;
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
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceImpl")
class NotificationServiceImplTest {

    @Mock private NotificationRequestRepository notificationRequestRepository;
    @Mock private NotificationHistoryRepository notificationHistoryRepository;
    @Mock private MovieRepository movieRepository;
    @Mock private TheaterRepository theaterRepository;
    @Mock private ScreenTypeRepository screenTypeRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageSender messageSender;   // 구체 클래스 아닌 인터페이스로 Mock
    @Mock private MovieCrawler cgvCrawler;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(
                notificationRequestRepository, notificationHistoryRepository,
                movieRepository, theaterRepository, screenTypeRepository,
                scheduleRepository, userRepository, messageSender,
                List.of(cgvCrawler)
        );
    }

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("존재하지 않는 chatId 이면 예외를 던진다")
        void throwsWhenUserNotFound() {
            given(userRepository.findByChatId("unknown")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.register(new NotificationRegisterRequest(
                    "unknown", "스파이더맨", "CGV", "영등포", "IMAX", LocalDate.now()
            ))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("정상 요청 시 NotificationRequest 를 저장한다")
        void savesRequestSuccessfully() {
            given(userRepository.findByChatId("111")).willReturn(Optional.of(user("111")));
            given(movieRepository.findByTitle("스파이더맨")).willReturn(Optional.of(movie("스파이더맨")));
            given(theaterRepository.findByBrandAndBranchName("CGV", "영등포"))
                    .willReturn(Optional.of(theater("CGV", "영등포")));
            given(screenTypeRepository.findByTypeName("IMAX"))
                    .willReturn(Optional.of(screenType("IMAX")));
            given(notificationRequestRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.register(new NotificationRegisterRequest(
                    "111", "스파이더맨", "CGV", "영등포", "IMAX", LocalDate.now()));

            then(notificationRequestRepository).should().save(any());
        }
    }

    @Nested
    @DisplayName("checkAndNotify()")
    class CheckAndNotify {

        @Test
        @DisplayName("대기 신청 없으면 크롤러를 호출하지 않는다")
        void skipsWhenNoPending() {
            given(notificationRequestRepository.findAllByTriggeredFalse()).willReturn(List.of());

            service.checkAndNotify();

            then(cgvCrawler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("매칭 상영 발견 시 MessageSender.sendAlert() 를 호출하고 triggered=true 로 변경한다")
        void notifiesOnMatch() {
            Movie movie = movie("스파이더맨");
            NotificationRequest req = NotificationRequest.builder()
                    .requestId(1L).user(user("123")).movie(movie)
                    .targetDate(LocalDate.of(2026, 9, 4)).build();

            given(notificationRequestRepository.findAllByTriggeredFalse()).willReturn(List.of(req));
            given(cgvCrawler.crawl(eq("스파이더맨"), any())).willReturn(List.of(screening("스파이더맨")));
            given(movieRepository.findByTitle(any())).willReturn(Optional.of(movie));
            given(theaterRepository.findByBrandAndBranchName(any(), any()))
                    .willReturn(Optional.of(theater("CGV", "강남")));
            given(screenTypeRepository.findByTypeName(any()))
                    .willReturn(Optional.of(screenType("일반")));
            given(scheduleRepository.findByMovieAndTheaterAndScreenTypeAndShowDateAndShowTime(
                    any(), any(), any(), any(), any())).willReturn(Optional.empty());
            given(scheduleRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(notificationHistoryRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.checkAndNotify();

            then(messageSender).should(times(1)).sendAlert(eq("123"), any());
            assert req.isTriggered();
        }

        @Test
        @DisplayName("지점 필터 불일치 시 알림을 보내지 않는다")
        void skipsWhenTheaterNotMatched() {
            Movie movie = movie("스파이더맨");
            NotificationRequest req = NotificationRequest.builder()
                    .requestId(1L).user(user("123")).movie(movie)
                    .theater(theater("CGV", "홍대"))   // 홍대 필터
                    .targetDate(LocalDate.of(2026, 9, 4)).build();

            given(notificationRequestRepository.findAllByTriggeredFalse()).willReturn(List.of(req));
            given(cgvCrawler.crawl(eq("스파이더맨"), any())).willReturn(List.of(screening("스파이더맨"))); // 강남 결과
            given(movieRepository.findByTitle(any())).willReturn(Optional.of(movie));
            given(theaterRepository.findByBrandAndBranchName(any(), any()))
                    .willReturn(Optional.of(theater("CGV", "강남")));
            given(screenTypeRepository.findByTypeName(any()))
                    .willReturn(Optional.of(screenType("일반")));
            given(scheduleRepository.findByMovieAndTheaterAndScreenTypeAndShowDateAndShowTime(
                    any(), any(), any(), any(), any())).willReturn(Optional.empty());
            given(scheduleRepository.save(any())).willAnswer(i -> i.getArgument(0));

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
