package io.github.chunyoungsang.moviebot.domain.user.repository;

import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByChatId(String chatId);
    List<User> findAllByActiveTrue();
}