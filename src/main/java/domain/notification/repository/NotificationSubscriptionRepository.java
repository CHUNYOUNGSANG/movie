package domain.notification.repository;

import domain.notification.entity.NotificationSubscription;
import domain.user.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, Long> {

    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    List<NotificationSubscription> findAllByTriggeredFalse();

    @EntityGraph(attributePaths = {"movie", "theater", "screenType"})
    List<NotificationSubscription> findByUser(User user);

    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    Optional<NotificationSubscription> findById(Long id);

    @EntityGraph(attributePaths = {"movie", "theater", "screenType", "user"})
    List<NotificationSubscription> findAllBySubscriptionIdIn(List<Long> subscriptionIds);
}