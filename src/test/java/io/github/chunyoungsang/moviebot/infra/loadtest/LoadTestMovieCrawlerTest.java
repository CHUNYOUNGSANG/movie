package io.github.chunyoungsang.moviebot.infra.loadtest;

import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.infra.cgv.CgvCrawler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("부하 테스트용 가짜 크롤러")
class LoadTestMovieCrawlerTest {

    @Test
    @DisplayName("설정한 시간만큼 기다린 뒤 예매가 열린 상영 하나를 돌려준다 (CGV 를 호출하지 않음)")
    void returnsOpenScreeningAfterDelay() {
        LoadTestMovieCrawler crawler = new LoadTestMovieCrawler(Duration.ofMillis(50));
        LocalDate date = LocalDate.of(2026, 12, 1);

        long start = System.nanoTime();
        List<ScreeningResult> results = crawler.crawl("부하테스트 영화 1", date);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.cinema()).isEqualTo("CGV");
            assertThat(r.movieTitle()).isEqualTo("부하테스트 영화 1");
            assertThat(r.date()).isEqualTo(date);
            assertThat(r.bookingOpen()).isTrue();
        });
    }

    @Test
    @DisplayName("loadtest 프로필에서만 켜지고, 그때 실제 CGV 크롤러는 꺼진다")
    void profilesAreExclusive() {
        assertThat(LoadTestMovieCrawler.class.getAnnotation(Profile.class).value()).containsExactly("loadtest");
        assertThat(CgvCrawler.class.getAnnotation(Profile.class).value()).containsExactly("!loadtest");
    }
}
