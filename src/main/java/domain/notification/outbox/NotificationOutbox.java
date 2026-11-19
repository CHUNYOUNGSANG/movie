package domain.notification.outbox;

import domain.notification.entity.NotificationSubscription;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_outbox")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationOutbox {

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

    @Column(length = 500)
    private String lastError;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
