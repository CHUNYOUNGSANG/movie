package domain.movie.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "target_movie")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetMovie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** "CGV" | "MEGABOX" | "ALL" */
    @Column(nullable = false)
    private String cinema;

    /** null이면 전체 지점. "영등포" 등 지점명 일부만 입력해도 포함 매칭 */
    private String theater;

    /** null이면 폴링 시점의 오늘 날짜 사용 */
    private LocalDate targetDate;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}