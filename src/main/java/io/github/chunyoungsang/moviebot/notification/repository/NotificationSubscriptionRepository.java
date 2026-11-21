package io.github.chunyoungsang.moviebot.notification.repository;

import io.github.chunyoungsang.moviebot.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.user.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 발송 대상으로 선점. 아직 처리되지 않은(triggered=false) 경우에만 true 로 바꾼다.
     * 여러 서버가 같은 구독을 동시에 매칭해도 행 잠금 + 조건 재확인으로 한 곳만 1을 받는다.
     */
    @Modifying
    @Query("update NotificationSubscription s set s.triggered = true " +
           "where s.subscriptionId = :id and s.triggered = false")
    int claimTriggered(@Param("id") Long subscriptionId);
}
