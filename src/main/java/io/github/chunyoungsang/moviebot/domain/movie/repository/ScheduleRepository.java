package io.github.chunyoungsang.moviebot.domain.movie.repository;

import io.github.chunyoungsang.moviebot.domain.movie.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
}