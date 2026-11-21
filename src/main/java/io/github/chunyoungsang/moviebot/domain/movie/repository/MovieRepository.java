package io.github.chunyoungsang.moviebot.domain.movie.repository;

import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, String> {
    Optional<Movie> findByTitle(String title);

    List<Movie> findAllByTitleIn(Collection<String> titles);
}