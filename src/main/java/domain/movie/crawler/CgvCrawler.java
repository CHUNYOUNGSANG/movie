package domain.movie.crawler;

import domain.movie.dto.ScreeningResult;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CGV 상영 스케줄 크롤러.
 * CSS 셀렉터 변경 시 parseItem() 내부만 수정.
 */
@Slf4j
@Component
public class CgvCrawler implements MovieCrawler {

    private static final String SCHEDULE_URL = "https://www.cgv.co.kr/common/showtimes/index.aspx";
    private static final String BOOKING_URL  = "https://www.cgv.co.kr/common/showtimes/index.aspx";
    private static final String USER_AGENT   =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public String getCinemaName() {
        return "CGV";
    }

    @Override
    public List<ScreeningResult> crawl(String movieTitle, LocalDate date) {
        List<ScreeningResult> results = new ArrayList<>();
        String dateParam = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        try {
            Document doc = Jsoup.connect(SCHEDULE_URL + "?date=" + dateParam)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .referrer("https://www.cgv.co.kr")
                    .timeout(15_000)
                    .get();

            Elements articles = doc.select("div.sect-showtimes article.movie-cont");
            log.debug("CGV: {}개 영화 섹션 파싱 중", articles.size());

            for (Element article : articles) {
                String title = article.select("strong.title").text().trim();
                if (title.isEmpty() || !title.contains(movieTitle)) continue;

                for (Element hall : article.select("div.type-hall")) {
                    String theater = hall.select("strong.cgv-txt-blue").text().trim();
                    for (Element item : hall.select("ul.info-timetable li")) {
                        parseItem(title, theater, item, date).ifPresent(results::add);
                    }
                }
            }
            log.info("CGV: [{}] {}개 상영 수집", movieTitle, results.size());

        } catch (IOException e) {
            log.error("CGV 크롤링 실패 (영화={}, 날짜={}): {}", movieTitle, date, e.getMessage());
        }
        return results;
    }

    private Optional<ScreeningResult> parseItem(
            String title, String theater, Element item, LocalDate date) {
        try {
            String timeStr  = item.select("em.cinema-time").text().trim();
            String seatsStr = item.select("span.seat").text().replaceAll("[^0-9]", "");
            boolean disabled = item.hasClass("disabled") || item.hasClass("soldout");

            LocalTime time = LocalTime.parse(timeStr.replaceAll("[^0-9:]", ""), TIME_FMT);
            int seats = seatsStr.isEmpty() ? -1 : Integer.parseInt(seatsStr);

            return Optional.of(new ScreeningResult(
                    "CGV", theater, title, date, time, seats, !disabled, BOOKING_URL
            ));
        } catch (Exception e) {
            log.debug("CGV 파싱 스킵: {}", e.getMessage());
            return Optional.empty();
        }
    }
}