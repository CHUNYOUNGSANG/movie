package domain.notification.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    /**
     * 발송 선점. PENDING 이고 발송 시각이 된 건만 SENDING 으로 바꾼다.
     * 리스너와 relay 가 같은 건을 동시에 집어도 조건부 UPDATE 이므로 한쪽만 1을 받는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = domain.notification.outbox.OutboxStatus.SENDING, o.lockedAt = :now
             where o.outboxId = :id
               and o.status = domain.notification.outbox.OutboxStatus.PENDING
               and o.nextAttemptAt <= :now
            """)
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("""
            select o.outboxId from NotificationOutbox o
             where o.status = domain.notification.outbox.OutboxStatus.PENDING
               and o.nextAttemptAt <= :now
             order by o.nextAttemptAt
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);

    /** 발송 중 서버가 죽어 SENDING 에 멈춘 건을 다시 PENDING 으로 되돌린다. */
    @Modifying
    @Query("""
            update NotificationOutbox o
               set o.status = domain.notification.outbox.OutboxStatus.PENDING, o.lockedAt = null
             where o.status = domain.notification.outbox.OutboxStatus.SENDING
               and o.lockedAt < :threshold
            """)
    int releaseStale(@Param("threshold") LocalDateTime threshold);
}
