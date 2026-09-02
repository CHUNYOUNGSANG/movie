package domain.movie.repository;

import domain.movie.entity.Movie;
import domain.movie.entity.Schedule;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    Optional<Schedule> findByMovieAndTheaterAndScreenTypeAndShowDateAndShowTime(
            Movie movie, Theater theater, ScreenType screenType,
            LocalDate showDate, LocalTime showTime);
}