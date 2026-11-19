package domain.movie.service;

import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.Schedule;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.repository.MovieRepository;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private jakarta.persistence.EntityManager entityManager;

    private MovieServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MovieServiceImpl(movieRepository, theaterRepository, screenTypeRepository, jdbcTemplate, entityManager);
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

}
