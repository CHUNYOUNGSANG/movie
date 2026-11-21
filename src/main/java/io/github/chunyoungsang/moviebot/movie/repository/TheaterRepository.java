package io.github.chunyoungsang.moviebot.movie.repository;

import io.github.chunyoungsang.moviebot.movie.entity.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TheaterRepository extends JpaRepository<Theater, String> {
    Optional<Theater> findByBrandAndBranchName(String brand, String branchName);

    List<Theater> findAllByBrandAndBranchNameIn(String brand, Collection<String> branchNames);
}
