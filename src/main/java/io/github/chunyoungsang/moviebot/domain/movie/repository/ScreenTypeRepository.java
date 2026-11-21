package io.github.chunyoungsang.moviebot.domain.movie.repository;

import io.github.chunyoungsang.moviebot.domain.movie.entity.ScreenType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScreenTypeRepository extends JpaRepository<ScreenType, Integer> {
    Optional<ScreenType> findByTypeName(String typeName);

    List<ScreenType> findAllByTypeNameIn(Collection<String> typeNames);
}