package io.github.chunyoungsang.moviebot.domain.notification.repository;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, Long> {

    /** 보관 기간이 지난 신청과 함께 지울 발송 이력 */
    @Modifying
    @Query("delete from NotificationHistory h where h.subscription.subscriptionId in :subscriptionIds")
    int deleteBySubscriptionIds(@Param("subscriptionIds") List<Long> subscriptionIds);
}
