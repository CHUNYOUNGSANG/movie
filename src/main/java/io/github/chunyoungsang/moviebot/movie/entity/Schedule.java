package io.github.chunyoungsang.moviebot.movie.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "schedules",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"movie_id", "theater_id", "screen_type_id", "show_date", "show_time"}
        ))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Schedule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long scheduleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id")
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theater_id")
    private Theater theater;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screen_type_id")
    private ScreenType screenType;

    private LocalDate showDate;
    private LocalTime showTime;

    @Column(name = "is_opened")
    private boolean opened;

    @UpdateTimestamp
    private LocalDateTime crawledAt;
}
