package io.github.chunyoungsang.moviebot.domain.notification.repository;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, Long> {
}