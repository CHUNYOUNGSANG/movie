package domain.notification.repository;

import domain.notification.entity.NotificationRequest;
import domain.user.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, Long> {

    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    List<NotificationRequest> findAllByTriggeredFalse();

    @EntityGraph(attributePaths = {"movie", "theater", "screenType"})
    List<NotificationRequest> findByUser(User user);

    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    Optional<NotificationRequest> findById(Long id);
}