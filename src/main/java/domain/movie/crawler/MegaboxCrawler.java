package domain.movie.crawler;

import domain.movie.dto.ScreeningResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 메가박스 상영 스케줄 크롤러.
 * 메가박스 내부 JSON API에 POST 요청.
 * API 응답 구조 변경 시 parseResponse() 내부만 수정.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MegaboxCrawler implements MovieCrawler {

    private static final String API_URL     = "https://www.megabox.co.kr/on/oh/ohb/movie/playScheduleList.do";
    private static final String BOOKING_URL = "https://www.megabox.co.kr/movie";
    private static final String USER_AGENT  =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final RestTemplate restTemplate;

    @Override
    public String getCinemaName() {
        return "MEGABOX";
    }

    @Override
    public List<ScreeningResult> crawl(String movieTitle, LocalDate date) {
        List<ScreeningResult> results = new ArrayList<>();
        String dateParam = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
            headers.set(HttpHeaders.REFERER, "https://www.megabox.co.kr/");

            Map<String, String> body = Map.of(
                    "brchNo", "0000",
                    "brchNm", "",
                    "playDe", dateParam,
                    "movieNo", "",
                    "screenNo", "",
                    "playStartTime", "",
                    "playEndTime", ""
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    API_URL, new HttpEntity<>(body, headers), Map.class);

            if (response != null) results.addAll(parseResponse(response, movieTitle, date));
            log.info("메가박스: [{}] {}개 상영 수집", movieTitle, results.size());

        } catch (Exception e) {
            log.error("메가박스 크롤링 실패 (영화={}, 날짜={}): {}", movieTitle, date, e.getMessage());
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private List<ScreeningResult> parseResponse(
            Map<String, Object> response, String movieTitle, LocalDate date) {
        List<ScreeningResult> results = new ArrayList<>();
        try {
            Map<String, Object> megaMap = (Map<String, Object>) response.get("megaMap");
            if (megaMap == null) return results;

            List<Map<String, Object>> movieList = (List<Map<String, Object>>) megaMap.get("movieList");
            if (movieList == null) return results;

            for (Map<String, Object> movie : movieList) {
                String title   = String.valueOf(movie.getOrDefault("movieNm", ""));
                String theater = String.valueOf(movie.getOrDefault("brchNm", "메가박스"));
                if (!title.contains(movieTitle)) continue;

                List<Map<String, Object>> schedules =
                        (List<Map<String, Object>>) movie.get("playSchdlList");
                if (schedules == null) continue;

                for (Map<String, Object> sched : schedules) {
                    parseSchedule(title, theater, sched, date).ifPresent(results::add);
                }
            }
        } catch (Exception e) {
            log.error("메가박스 응답 파싱 실패: {}", e.getMessage());
        }
        return results;
    }

    private Optional<ScreeningResult> parseSchedule(
            String title, String theater, Map<String, Object> sched, LocalDate date) {
        try {
            String startTime = String.valueOf(sched.getOrDefault("startTime", "")).trim();
            int restSeats    = toInt(sched.getOrDefault("restSeatCnt", -1));
            boolean saleYn   = "Y".equals(sched.getOrDefault("saleYn", "N"));

            LocalTime time = LocalTime.parse(startTime, TIME_FMT);
            return Optional.of(new ScreeningResult(
                    "MEGABOX", theater, title, date, time, restSeats, saleYn, BOOKING_URL
            ));
        } catch (Exception e) {
            log.debug("메가박스 파싱 스킵: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private int toInt(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { return -1; }
    }
}