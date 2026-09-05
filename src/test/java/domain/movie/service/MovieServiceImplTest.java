package domain.movie.service;

import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.Schedule;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScheduleRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("MovieServiceImpl")
class MovieServiceImplTest {

    @Mock private MovieRepository movieRepository;
    @Mock private TheaterRepository theaterRepository;
    @Mock private ScreenTypeRepository screenTypeRepository;
    @Mock private ScheduleRepository scheduleRepository;

    private MovieServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MovieServiceImpl(movieRepository, theaterRepository, screenTypeRepository, scheduleRepository);
    }

    @Nested
    @DisplayName("findOrCreateMovie()")
    class FindOrCreateMovie {

        @Test
        @DisplayName("이미 있으면 그대로 반환한다")
        void returnsExisting() {
            Movie existing = Movie.builder().movieId("스파이더맨").title("스파이더맨").build();
            given(movieRepository.findByTitle("스파이더맨")).willReturn(Optional.of(existing));

            Movie result = service.findOrCreateMovie("스파이더맨");

            assertThat(result).isSameAs(existing);
            then(movieRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("없으면 새로 만들어 저장한다")
        void createsWhenAbsent() {
            given(movieRepository.findByTitle("파묘")).willReturn(Optional.empty());
            given(movieRepository.save(any())).willAnswer(i -> i.getArgument(0));

            Movie result = service.findOrCreateMovie("파묘");

            assertThat(result.getMovieId()).isEqualTo("파묘");
            assertThat(result.getTitle()).isEqualTo("파묘");
        }
    }

    @Nested
    @DisplayName("findOrCreateTheater()")
    class FindOrCreateTheater {

        @Test
        @DisplayName("이미 있으면 그대로 반환한다")
        void returnsExisting() {
            Theater existing = Theater.builder().theaterId("CGV|영등포").brand("CGV").branchName("영등포").build();
            given(theaterRepository.findByBrandAndBranchName("CGV", "영등포")).willReturn(Optional.of(existing));

            Theater result = service.findOrCreateTheater("CGV", "영등포");

            assertThat(result).isSameAs(existing);
        }

        @Test
        @DisplayName("없으면 brand|branchName 형식의 id로 새로 만든다")
        void createsWhenAbsent() {
            given(theaterRepository.findByBrandAndBranchName("CGV", "강남")).willReturn(Optional.empty());
            given(theaterRepository.save(any())).willAnswer(i -> i.getArgument(0));

            Theater result = service.findOrCreateTheater("CGV", "강남");

            assertThat(result.getTheaterId()).isEqualTo("CGV|강남");
        }
    }

    @Nested
    @DisplayName("findOrCreateScreenType()")
    class FindOrCreateScreenType {

        @Test
        @DisplayName("없으면 새로 만든다")
        void createsWhenAbsent() {
            given(screenTypeRepository.findByTypeName("IMAX")).willReturn(Optional.empty());
            given(screenTypeRepository.save(any())).willAnswer(i -> i.getArgument(0));

            ScreenType result = service.findOrCreateScreenType("IMAX");

            assertThat(result.getTypeName()).isEqualTo("IMAX");
        }
    }

    @Nested
    @DisplayName("findOrCreateTheaterFromResult()")
    class FindOrCreateTheaterFromResult {

        @Test
        @DisplayName("siteNm에서 지점명을 뽑아내고 bookingUrl에서 siteNo를 파싱해 새로 만든다")
        void createsWithParsedSiteNo() {
            ScreeningResult result = new ScreeningResult(
                    "CGV", "CGV 영등포타임스퀘어 SCREENX관", "스파이더맨",
                    LocalDate.of(2026, 9, 4), LocalTime.of(14, 0), 50, true,
                    "https://www.cgv.co.kr/reservation/?movNo=1&siteNo=0059");
            given(theaterRepository.findByBrandAndBranchName("CGV", "영등포타임스퀘어")).willReturn(Optional.empty());
            given(theaterRepository.save(any())).willAnswer(i -> i.getArgument(0));

            Theater theater = service.findOrCreateTheaterFromResult(result);

            assertThat(theater.getBranchName()).isEqualTo("영등포타임스퀘어");
            assertThat(theater.getSiteNo()).isEqualTo("0059");
            assertThat(theater.getTheaterId()).isEqualTo("CGV|영등포타임스퀘어");
        }

        @Test
        @DisplayName("이미 있고 siteNo가 비어있으면 채워준다")
        void fillsMissingSiteNoOnExisting() {
            Theater existing = Theater.builder().theaterId("CGV|강남").brand("CGV").branchName("강남").build();
            ScreeningResult result = new ScreeningResult(
                    "CGV", "CGV 강남 IMAX관", "스파이더맨",
                    LocalDate.of(2026, 9, 4), LocalTime.of(14, 0), 50, true,
                    "https://www.cgv.co.kr/reservation/?movNo=1&siteNo=0056");
            given(theaterRepository.findByBrandAndBranchName("CGV", "강남")).willReturn(Optional.of(existing));

            Theater theater = service.findOrCreateTheaterFromResult(result);

            assertThat(theater).isSameAs(existing);
            assertThat(theater.getSiteNo()).isEqualTo("0056");
        }

        @Test
        @DisplayName("이미 siteNo가 있으면 덮어쓰지 않는다")
        void doesNotOverwriteExistingSiteNo() {
            Theater existing = Theater.builder()
                    .theaterId("CGV|강남").brand("CGV").branchName("강남").siteNo("9999").build();
            ScreeningResult result = new ScreeningResult(
                    "CGV", "CGV 강남 IMAX관", "스파이더맨",
                    LocalDate.of(2026, 9, 4), LocalTime.of(14, 0), 50, true,
                    "https://www.cgv.co.kr/reservation/?movNo=1&siteNo=0056");
            given(theaterRepository.findByBrandAndBranchName("CGV", "강남")).willReturn(Optional.of(existing));

            Theater theater = service.findOrCreateTheaterFromResult(result);

            assertThat(theater.getSiteNo()).isEqualTo("9999");
        }
    }

    @Nested
    @DisplayName("saveOrUpdateSchedule()")
    class SaveOrUpdateSchedule {

        @Test
        @DisplayName("기존 스케줄이 없으면 새로 저장한다")
        void savesNewSchedule() {
            Movie movie = Movie.builder().movieId("스파이더맨").title("스파이더맨").build();
            Theater theater = Theater.builder().theaterId("CGV|강남").brand("CGV").branchName("강남").build();
            ScreenType screenType = ScreenType.builder().screenTypeId(1).typeName("일반").build();
            ScreeningResult result = new ScreeningResult(
                    "CGV", "CGV 강남", "스파이더맨",
                    LocalDate.of(2026, 9, 4), LocalTime.of(14, 0), 50, true, "https://cgv.co.kr");

            given(scheduleRepository.findByMovieAndTheaterAndScreenTypeAndShowDateAndShowTime(
                    movie, theater, screenType, result.date(), result.time())).willReturn(Optional.empty());

            service.saveOrUpdateSchedule(movie, theater, screenType, result);

            then(scheduleRepository).should().save(any(Schedule.class));
        }

        @Test
        @DisplayName("기존 스케줄이 있으면 open 여부만 갱신하고 새로 저장하지 않는다")
        void updatesExistingSchedule() {
            Movie movie = Movie.builder().movieId("스파이더맨").title("스파이더맨").build();
            Theater theater = Theater.builder().theaterId("CGV|강남").brand("CGV").branchName("강남").build();
            ScreenType screenType = ScreenType.builder().screenTypeId(1).typeName("일반").build();
            ScreeningResult result = new ScreeningResult(
                    "CGV", "CGV 강남", "스파이더맨",
                    LocalDate.of(2026, 9, 4), LocalTime.of(14, 0), 50, true, "https://cgv.co.kr");
            Schedule existing = Schedule.builder()
                    .movie(movie).theater(theater).screenType(screenType)
                    .showDate(result.date()).showTime(result.time()).opened(false).build();

            given(scheduleRepository.findByMovieAndTheaterAndScreenTypeAndShowDateAndShowTime(
                    movie, theater, screenType, result.date(), result.time())).willReturn(Optional.of(existing));

            service.saveOrUpdateSchedule(movie, theater, screenType, result);

            assertThat(existing.isOpened()).isTrue();
            then(scheduleRepository).should(never()).save(any());
        }
    }
}
