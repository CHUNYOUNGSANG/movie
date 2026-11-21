package io.github.chunyoungsang.moviebot.domain.notification.sender;

import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 예매 오픈 알림 문구를 만든다 (영화·날짜·극장별 상영 시간·예매 링크).
 * 어떤 정보를 알려줄지는 알림 도메인의 규칙이라 발송 채널과 분리했다.
 * 문구는 텔레그램 HTML 모드(parse_mode=HTML)에서 쓰는 태그(<b>, <a>)를 사용한다.
 */
@Component
public class AlertMessageFormatter {

    public String format(List<ScreeningResult> results) {
        ScreeningResult first = results.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("🎬 <b>예매 오픈 알림!</b>\n\n");
        sb.append("영화: <b>").append(first.movieTitle()).append("</b>\n");
        sb.append("날짜: ").append(first.date()).append("\n");

        // 극장별로 그룹핑해서 출력
        Map<String, List<ScreeningResult>> byTheater = new LinkedHashMap<>();
        for (ScreeningResult r : results) {
            byTheater.computeIfAbsent(r.theater(), k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<ScreeningResult>> entry : byTheater.entrySet()) {
            sb.append("\n📍 <b>").append(entry.getKey()).append("</b>\n");
            for (ScreeningResult r : entry.getValue()) {
                String time  = r.time() != null ? r.time().toString() : "미정";
                String seats = r.availableSeats() < 0 ? "" : " (잔여 " + r.availableSeats() + "석)";
                sb.append("  ▸ ").append(time).append(seats).append("\n");
            }
        }

        sb.append("\n👉 <a href=\"").append(first.bookingUrl()).append("\">예매하기</a>");
        return sb.toString();
    }
}
