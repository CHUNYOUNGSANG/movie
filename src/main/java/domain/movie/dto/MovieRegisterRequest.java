package domain.movie.dto;

import java.time.LocalDate;

/** cinema: "CGV" | "MEGABOX" | "ALL" / theater: null이면 전체 지점 */
public record MovieRegisterRequest(
        String title,
        String cinema,
        String theater,
        LocalDate targetDate
) {}