package domain.notification.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_histories")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private NotificationRequest request;

    @CreationTimestamp
    private LocalDateTime sentAt;

    @Builder.Default
    private String status = "SUCCESS";
}