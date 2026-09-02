package domain.movie.repository;

import domain.movie.entity.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TheaterRepository extends JpaRepository<Theater, String> {
    Optional<Theater> findByBrandAndBranchName(String brand, String branchName);
}
