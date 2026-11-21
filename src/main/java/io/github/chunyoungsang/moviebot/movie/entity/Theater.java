package io.github.chunyoungsang.moviebot.movie.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "theaters")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Theater {

    @Id
    private String theaterId;   // "CGV|영등포타임스퀘어"

    @Column(nullable = false)
    private String brand;       // CGV

    @Column(nullable = false)
    private String branchName;  // 영등포타임스퀘어

    private String siteNo;      // CGV 내부 극장 코드 (e.g. "0059")

    private String region;
}
