package domain.movie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "movies")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Movie {

    @Id
    private String movieId;   // 영화 제목 그대로 사용

    @Column(nullable = false)
    private String title;

    private String movNo;     // CGV 내부 영화 코드 (e.g. "30001192")

    private LocalDate releaseDate;

    @Builder.Default
    private String status = "상영중";
}