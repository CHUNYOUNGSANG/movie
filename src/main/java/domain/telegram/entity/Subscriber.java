package domain.telegram.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriber")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class Subscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String chatId;

    private String username;

    private boolean active;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
