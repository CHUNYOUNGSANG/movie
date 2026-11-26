package io.github.chunyoungsang.moviebot.infra.loadtest;

import io.github.chunyoungsang.moviebot.domain.movie.crawler.CrawlException;
import io.github.chunyoungsang.moviebot.domain.movie.crawler.MovieCrawler;
import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 부하 테스트 전용 가짜 크롤러 (loadtest 프로필에서만 켜짐, 그때 CgvCrawler 는 꺼짐).
 * 실제 CGV 를 호출하지 않고, 크롤링 시간만큼 기다린 뒤 "예매 열림" 상영 하나를 돌려준다.
 * 신청에 지점·관을 넣지 않으면 모두 매칭되어 알림 발송량을 신청 수만큼 만들 수 있다.
 */
@Component
@Profile("loadtest")
public class LoadTestMovieCrawler implements MovieCrawler {

    private final Duration delay;

    public LoadTestMovieCrawler(@Value("${loadtest.crawl.delay:PT2S}") Duration delay) {
        this.delay = delay;
    }

    @Override
    public String getCinemaName() {
        return "CGV";
    }

    @Override
    public List<ScreeningResult> crawl(String movieTitle, LocalDate date) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 종료 시 주기 취소
            throw new CrawlException("interrupted", "가짜 크롤링 중단", e);
        }
        return List.of(new ScreeningResult(
                "CGV", "CGV 용산아이파크몰 IMAX관", movieTitle, date, LocalTime.of(19, 0),
                100, true, "https://example.invalid/loadtest"));
    }
}
