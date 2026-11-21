package io.github.chunyoungsang.moviebot.movie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "screen_types")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ScreenType {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer screenTypeId;

    @Column(unique = true, nullable = false)
    private String typeName;   // IMAX, ScreenX, 4DX, 일반

    /** 상영관 텍스트에서 관 타입을 추론. 새 타입 추가 시 여기만 수정. */
    public static String extractFrom(String theaterText) {
        String upper = theaterText.toUpperCase();
        if (upper.contains("IMAX"))    return "IMAX";
        if (upper.contains("SCREENX")) return "ScreenX";
        if (upper.contains("4DX"))     return "4DX";
        if (upper.contains("SUITE"))   return "SUITE";
        return "일반";
    }
}
