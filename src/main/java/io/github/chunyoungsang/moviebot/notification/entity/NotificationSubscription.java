package io.github.chunyoungsang.moviebot.notification.entity;

import io.github.chunyoungsang.moviebot.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_subscriptions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationSubscription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long subscriptionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id")
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theater_id")
    private Theater theater;       // null = 전체 지점

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screen_type_id")
    private ScreenType screenType; // null = 전체 관

    private LocalDate targetDate;

    @Column(name = "is_triggered")
    @Builder.Default
    private boolean triggered = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** SRP: 매칭 판단은 자신이 가장 잘 안다. */
    public boolean matches(io.github.chunyoungsang.moviebot.movie.dto.ScreeningResult result) {
        if (theater != null && !result.theater().contains(theater.getBranchName())) return false;
        if (screenType != null &&
                !result.theater().toUpperCase().contains(screenType.getTypeName().toUpperCase())) return false;
        return true;
    }
}