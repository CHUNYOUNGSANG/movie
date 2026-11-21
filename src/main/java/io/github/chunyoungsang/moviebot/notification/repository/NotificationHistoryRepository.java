package io.github.chunyoungsang.moviebot.notification.repository;

import io.github.chunyoungsang.moviebot.notification.entity.NotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, Long> {
}