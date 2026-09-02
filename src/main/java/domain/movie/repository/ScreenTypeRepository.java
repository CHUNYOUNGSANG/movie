package domain.movie.repository;

import domain.movie.entity.ScreenType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScreenTypeRepository extends JpaRepository<ScreenType, Integer> {
    Optional<ScreenType> findByTypeName(String typeName);
}