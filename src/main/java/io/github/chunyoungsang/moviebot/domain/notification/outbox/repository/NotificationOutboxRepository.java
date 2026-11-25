package io.github.chunyoungsang.moviebot.domain.notification.outbox.repository;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.NotificationOutbox;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.OutboxStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    /** 보관 기간이 지난 신청과 함께 지울 Outbox (신청 삭제 전에 지워야 FK 위반이 없다) */
    @Modifying
    @Query("delete from NotificationOutbox o where o.subscription.subscriptionId in :subscriptionIds")
    int deleteBySubscriptionIds(@Param("subscriptionIds") List<Long> subscriptionIds);

    /**
     * 발송 선점. PENDING 이고 발송 시각이 된 건만 SENDING 으로 바꾼다.
     * 리스너와 relay 가 같은 건을 동시에 집어도 조건부 UPDATE 이므로 한쪽만 1을 받는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = SENDING, o.lockedAt = :now
             where o.outboxId = :id
               and o.status = PENDING
               and o.nextAttemptAt <= :now
            """)
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("""
            select o.outboxId from NotificationOutbox o
             where o.status = PENDING
               and o.nextAttemptAt <= :now
             order by o.nextAttemptAt
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);

    /** 구독별 발송 상태 (오래된 것부터, 같은 구독에 여러 건이면 마지막 것이 최신) */
    @Query("""
            select o.subscription.subscriptionId as subscriptionId, o.status as status
              from NotificationOutbox o
             where o.subscription.subscriptionId in :subscriptionIds
             order by o.outboxId
            """)
    List<DeliveryStatusView> findStatusesBySubscriptionIds(@Param("subscriptionIds") List<Long> subscriptionIds);

    interface DeliveryStatusView {
        Long getSubscriptionId();
        OutboxStatus getStatus();
    }

    /** 모니터링용: 상태별 건수 (SENT 는 계속 늘어나기만 하므로 제외) */
    @Query("""
            select o.status as status, count(o) as count
              from NotificationOutbox o
             where o.status <> SENT
             group by o.status
            """)
    List<StatusCountView> countUnsentByStatus();

    interface StatusCountView {
        OutboxStatus getStatus();
        long getCount();
    }

    /** 모니터링용: 가장 오래 기다리고 있는 PENDING 의 생성 시각 */
    @Query("""
            select min(o.createdAt) from NotificationOutbox o
             where o.status = PENDING
            """)
    LocalDateTime findOldestPendingCreatedAt();

    /** 발송 중 서버가 죽어 SENDING 에 멈춘 건을 다시 PENDING 으로 되돌린다. */
    @Modifying
    @Query("""
            update NotificationOutbox o
               set o.status = PENDING, o.lockedAt = null
             where o.status = SENDING
               and o.lockedAt < :threshold
            """)
    int releaseStale(@Param("threshold") LocalDateTime threshold);
}
