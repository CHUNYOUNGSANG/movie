package io.github.chunyoungsang.moviebot.movie.repository;

import io.github.chunyoungsang.moviebot.movie.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
}