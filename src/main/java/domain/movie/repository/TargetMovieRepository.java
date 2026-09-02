package domain.movie.repository;

import domain.movie.entity.TargetMovie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TargetMovieRepository extends JpaRepository<TargetMovie, Long> {

    List<TargetMovie> findAllByActiveTrue();
}