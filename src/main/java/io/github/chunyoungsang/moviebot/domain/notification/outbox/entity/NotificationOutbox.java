package io.github.chunyoungsang.moviebot.domain.notification.outbox.entity;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 보낼 알림 한 건과 발송 상태.
 *
 * 상태 전이 (PENDING → SENDING 선점은 동시성 때문에 리포지토리의 조건부 UPDATE 로 한다)
 *   PENDING ──claim──▶ SENDING ──markSent──▶ SENT
 *      ▲                  │
 *      └──scheduleRetry───┤
 *                         └──giveUp──▶ DEAD
 * SENT, DEAD 는 끝난 상태라 다시 바꿀 수 없다.
 * 빌더는 테스트 픽스처·조회용이다. 상태를 바꿀 때는 아래 메서드만 쓴다 (setter 없음).
 */
@Entity
@Table(name = "notification_outbox")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationOutbox {

    static final int ERROR_MAX_LENGTH = 500;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long outboxId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private NotificationSubscription subscription;

    @Column(nullable = false)
    private String chatId;

    @Column(nullable = false, length = 4096)
    private String message;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // MySQL 에서 ENUM 컬럼 타입으로 매핑되지 않도록 고정
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    private LocalDateTime lockedAt;

    @Column(length = ERROR_MAX_LENGTH)
    private String lastError;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** 새 알림. 바로 발송할 수 있는 PENDING 으로 만든다 */
    public static NotificationOutbox create(NotificationSubscription subscription, String message, LocalDateTime now) {
        return NotificationOutbox.builder()
                .subscription(subscription)
                .chatId(subscription.getUser().getChatId())
                .message(message)
                .nextAttemptAt(now)
                .build();
    }

    /** 발송 성공 */
    public void markSent() {
        requireNotFinished("markSent");
        this.status = OutboxStatus.SENT;
        this.attemptCount++;
        this.lockedAt = null;
    }

    /** 발송 실패, nextAttemptAt 에 다시 시도 (속도 제한이면 attempts 가 그대로일 수 있다) */
    public void scheduleRetry(int attempts, LocalDateTime nextAttemptAt, String error) {
        requireNotFinished("scheduleRetry");
        this.status = OutboxStatus.PENDING;
        this.attemptCount = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
        this.lockedAt = null;
    }

    /** 재시도 포기 */
    public void giveUp(int attempts, String error) {
        requireNotFinished("giveUp");
        this.status = OutboxStatus.DEAD;
        this.attemptCount = attempts;
        this.lastError = truncate(error);
        this.lockedAt = null;
    }

    private void requireNotFinished(String action) {
        if (status.isFinished()) {
            throw new IllegalStateException("이미 끝난 Outbox(" + outboxId + ", " + status + ")는 " + action + " 할 수 없음");
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= ERROR_MAX_LENGTH ? s : s.substring(0, ERROR_MAX_LENGTH);
    }
}
