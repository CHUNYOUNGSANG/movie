package io.github.chunyoungsang.moviebot.domain.movie.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 크롤러가 반환하는 상영 정보 값 객체.
 * availableSeats = -1 이면 잔여석 정보 없음.
 */
public record ScreeningResult(
        String cinema,
        String theater,
        String movieTitle,
        LocalDate date,
        LocalTime time,
        int availableSeats,
        boolean bookingOpen,
        String bookingUrl
) {
    /** 중복 알림 방지용 고유 키 */
    public String uniqueKey() {
        return String.join("|", cinema, theater, movieTitle,
                date.toString(),
                time != null ? time.toString() : "");
    }
}