package domain.movie.service;

import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.MovieRegisterRequest;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.TargetMovie;
import domain.movie.repository.TargetMovieRepository;
import domain.telegram.service.TelegramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MovieServiceImpl")
class MovieServiceImplTest {

    @Mock private TargetMovieRepository targetMovieRepository;
    @Mock private MovieCrawler cgvCrawler;
    @Mock private MovieCrawler megaboxCrawler;
    @Mock private TelegramService telegramService;

    private MovieServiceImpl movieService;

    @BeforeEach
    void setUp() {
        // List<MovieCrawler> 직접 주입 — @InjectMocks로는 컬렉션 주입 불가
        movieService = new MovieServiceImpl(
                targetMovieRepository,
                List.of(cgvCrawler, megaboxCrawler),
                telegramService
        );
        lenient().when(cgvCrawler.getCinemaName()).thenReturn("CGV");
        lenient().when(megaboxCrawler.getCinemaName()).thenReturn("MEGABOX");
    }

    // ────────────────────────────────────────────────
    // register()
    // ────────────────────────────────────────────────
    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("정상 요청 시 엔티티를 저장하고 반환한다")
        void success() {
            MovieRegisterRequest request = new MovieRegisterRequest("파묘", "CGV", null, LocalDate.now());
            TargetMovie saved = savedEntity(1L, "파묘", "CGV");
            given(targetMovieRepository.save(any())).willReturn(saved);

            TargetMovie result = movieService.register(request);

            assertThat(result.getTitle()).isEqualTo("파묘");
            assertThat(result.getCinema()).isEqualTo("CGV");
            assertThat(result.isActive()).isTrue();
            then(targetMovieRepository).should().save(any());
        }

        @Test
        @DisplayName("cinema가 null이면 ALL로 저장된다")
        void cinemaDefaultsToAll() {
            MovieRegisterRequest request = new MovieRegisterRequest("파묘", null, null, LocalDate.now());
            TargetMovie saved = savedEntity(1L, "파묘", "ALL");
            given(targetMovieRepository.save(any())).willReturn(saved);

            TargetMovie result = movieService.register(request);

            assertThat(result.getCinema()).isEqualTo("ALL");
        }
    }

    // ────────────────────────────────────────────────
    // checkAndNotify()
    // ────────────────────────────────────────────────
    @Nested
    @DisplayName("checkAndNotify()")
    class CheckAndNotify {

        @Test
        @DisplayName("활성화된 영화가 없으면 크롤링하지 않는다")
        void doNothingWhenNoActiveTargets() {
            given(targetMovieRepository.findAllByActiveTrue()).willReturn(List.of());

            movieService.checkAndNotify();

            then(cgvCrawler).shouldHaveNoInteractions();
            then(megaboxCrawler).shouldHaveNoInteractions();
            then(telegramService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("새 상영 발견 시 텔레그램 알림을 발송한다")
        void sendsAlertForNewScreening() {
            TargetMovie target = activeTarget("파묘", "CGV");
            given(targetMovieRepository.findAllByActiveTrue()).willReturn(List.of(target));

            ScreeningResult screening = screeningOf("파묘", "CGV");
            given(cgvCrawler.crawl(eq("파묘"), any(LocalDate.class))).willReturn(List.of(screening));

            movieService.checkAndNotify();

            then(telegramService).should(times(1)).sendAlert(screening);
        }

        @Test
        @DisplayName("동일 상영은 중복 알림을 발송하지 않는다")
        void suppressesDuplicateAlert() {
            TargetMovie target = activeTarget("파묘", "CGV");
            given(targetMovieRepository.findAllByActiveTrue()).willReturn(List.of(target));

            ScreeningResult screening = screeningOf("파묘", "CGV");
            given(cgvCrawler.crawl(eq("파묘"), any(LocalDate.class))).willReturn(List.of(screening));

            movieService.checkAndNotify(); // 1회
            movieService.checkAndNotify(); // 2회

            then(telegramService).should(times(1)).sendAlert(screening); // 한 번만 발송
        }

        @Test
        @DisplayName("cinema=CGV이면 CGV 크롤러만 호출한다")
        void callsOnlyMatchingCrawler() {
            TargetMovie target = activeTarget("파묘", "CGV");
            given(targetMovieRepository.findAllByActiveTrue()).willReturn(List.of(target));
            given(cgvCrawler.crawl(any(), any())).willReturn(List.of());

            movieService.checkAndNotify();

            then(cgvCrawler).should().crawl(eq("파묘"), any());
            then(megaboxCrawler).should(never()).crawl(any(), any());
        }

        @Test
        @DisplayName("cinema=ALL이면 모든 크롤러를 호출한다")
        void callsAllCrawlersWhenCinemaIsAll() {
            TargetMovie target = activeTarget("파묘", "ALL");
            given(targetMovieRepository.findAllByActiveTrue()).willReturn(List.of(target));
            given(cgvCrawler.crawl(any(), any())).willReturn(List.of());
            given(megaboxCrawler.crawl(any(), any())).willReturn(List.of());

            movieService.checkAndNotify();

            then(cgvCrawler).should().crawl(eq("파묘"), any());
            then(megaboxCrawler).should().crawl(eq("파묘"), any());
        }
    }

    // ────────────────────────────────────────────────
    // deactivate()
    // ────────────────────────────────────────────────
    @Nested
    @DisplayName("deactivate()")
    class Deactivate {

        @Test
        @DisplayName("존재하는 ID이면 active를 false로 변경한다")
        void deactivatesSuccessfully() {
            TargetMovie target = activeTarget("파묘", "CGV");
            given(targetMovieRepository.findById(1L)).willReturn(Optional.of(target));

            movieService.deactivate(1L);

            assertThat(target.isActive()).isFalse();
        }

        @Test
        @DisplayName("존재하지 않는 ID이면 예외를 발생시킨다")
        void throwsWhenNotFound() {
            given(targetMovieRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> movieService.deactivate(999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("999");
        }
    }

    // ────────────────────────────────────────────────
    // 헬퍼 메서드
    // ────────────────────────────────────────────────
    private TargetMovie savedEntity(Long id, String title, String cinema) {
        return TargetMovie.builder()
                .id(id).title(title).cinema(cinema)
                .active(true).createdAt(LocalDateTime.now()).build();
    }

    private TargetMovie activeTarget(String title, String cinema) {
        return TargetMovie.builder()
                .title(title).cinema(cinema).active(true).build();
    }

    private ScreeningResult screeningOf(String title, String cinema) {
        return new ScreeningResult(
                cinema, "강남", title,
                LocalDate.now(), LocalTime.of(14, 0),
                50, true, "https://example.com"
        );
    }
}