package io.github.chunyoungsang.moviebot.notification.sender;

import io.github.chunyoungsang.moviebot.movie.dto.ScreeningResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AlertMessageFormatter")
class AlertMessageFormatterTest {

    private final AlertMessageFormatter formatter = new AlertMessageFormatter();

    @Test
    @DisplayName("영화/극장/시간 정보를 담은 메시지를 만든다")
    void containsScreeningInfo() {
        ScreeningResult result = new ScreeningResult(
                "CGV", "강남", "파묘",
                LocalDate.of(2026, 9, 10), LocalTime.of(14, 30),
                50, true, "https://cgv.co.kr");

        String message = formatter.format(List.of(result));

        assertThat(message).contains("파묘", "강남", "14:30");
    }

    @Test
    @DisplayName("같은 극장의 상영은 한 번만 극장명을 쓰고 시간을 나열한다")
    void groupsByTheater() {
        LocalDate date = LocalDate.of(2026, 9, 10);
        String message = formatter.format(List.of(
                new ScreeningResult("CGV", "강남", "파묘", date, LocalTime.of(14, 30), 50, true, "https://cgv.co.kr"),
                new ScreeningResult("CGV", "강남", "파묘", date, LocalTime.of(19, 0), -1, true, "https://cgv.co.kr")));

        assertThat(message.split("📍").length - 1).as("극장 헤더 수").isEqualTo(1);
        assertThat(message).contains("14:30 (잔여 50석)", "▸ 19:00\n");   // 잔여 좌석 -1 이면 좌석 수 생략
    }
}
