package io.github.chunyoungsang.moviebot.movie.crawler;

import io.github.chunyoungsang.moviebot.movie.dto.ScreeningResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright(브라우저)에 의존하는 crawl()은 라이브 컨텍스트가 있어야 해서 단위 테스트 대상에서 제외.
 * 순수 로직(사이트 코드 매핑, 시간 파싱, JSON→DTO 변환)만 리플렉션으로 직접 검증.
 */
@DisplayName("CgvCrawler")
class CgvCrawlerTest {

    private final CgvCrawler crawler = new CgvCrawler(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    @Nested
    @DisplayName("resolveSiteNos()")
    class ResolveSiteNos {

        @Test
        @DisplayName("알려진 지점명이 포함되면 해당 siteNo 하나만 반환한다")
        void returnsSingleSiteNoForKnownBranch() throws Exception {
            List<String> result = invokeSiteNos("영등포타임스퀘어");

            assertThat(result).containsExactly("0059");
        }

        @Test
        @DisplayName("모르는 지점명이면 전체 siteNo 목록을 반환한다")
        void returnsAllSiteNosForUnknownBranch() throws Exception {
            List<String> result = invokeSiteNos("존재하지않는극장");

            assertThat(result).hasSize(8);
        }

        @Test
        @DisplayName("siteHint가 null이면 전체 siteNo 목록을 반환한다")
        void returnsAllSiteNosWhenHintIsNull() throws Exception {
            List<String> result = invokeSiteNos(null);

            assertThat(result).hasSize(8);
        }

        @SuppressWarnings("unchecked")
        private List<String> invokeSiteNos(String siteHint) throws Exception {
            Method method = CgvCrawler.class.getDeclaredMethod("resolveSiteNos", String.class);
            method.setAccessible(true);
            return (List<String>) method.invoke(crawler, siteHint);
        }
    }

    @Nested
    @DisplayName("parseTime()")
    class ParseTime {

        @Test
        @DisplayName("4자리 HHmm을 파싱한다")
        void parsesFourDigitTime() throws Exception {
            assertThat(invokeParseTime("1430")).isEqualTo(LocalTime.of(14, 30));
        }

        @Test
        @DisplayName("3자리(앞자리 0 누락)도 파싱한다")
        void parsesThreeDigitTimeWithLeadingZero() throws Exception {
            assertThat(invokeParseTime("840")).isEqualTo(LocalTime.of(8, 40));
        }

        @Test
        @DisplayName("null이나 빈 문자열이면 null을 반환한다")
        void returnsNullForBlank() throws Exception {
            assertThat(invokeParseTime(null)).isNull();
            assertThat(invokeParseTime("")).isNull();
        }

        private LocalTime invokeParseTime(String hhmm) throws Exception {
            Method method = CgvCrawler.class.getDeclaredMethod("parseTime", String.class);
            method.setAccessible(true);
            return (LocalTime) method.invoke(crawler, hhmm);
        }
    }

    @Nested
    @DisplayName("toResult()")
    class ToResult {

        @Test
        @DisplayName("CGV API 응답 JSON을 ScreeningResult로 변환한다")
        void convertsJsonToScreeningResult() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode item = mapper.readTree("""
                    {
                        "siteNm": "CGV 영등포타임스퀘어",
                        "scnsNm": "SCREENX관 (리클라이너)",
                        "movNm": "스파이더맨-브랜드 뉴 데이",
                        "scnYmd": "20260907",
                        "scnsrtTm": "0840",
                        "frSeatCnt": 42,
                        "movNo": "1234",
                        "siteNo": "0059"
                    }
                    """);

            Method method = CgvCrawler.class.getDeclaredMethod("toResult", JsonNode.class);
            method.setAccessible(true);
            ScreeningResult result = (ScreeningResult) method.invoke(crawler, item);

            assertThat(result.cinema()).isEqualTo("CGV");
            assertThat(result.theater()).isEqualTo("CGV 영등포타임스퀘어 SCREENX관 (리클라이너)");
            assertThat(result.movieTitle()).isEqualTo("스파이더맨-브랜드 뉴 데이");
            assertThat(result.date()).isEqualTo(LocalDate.of(2026, 9, 7));
            assertThat(result.time()).isEqualTo(LocalTime.of(8, 40));
            assertThat(result.availableSeats()).isEqualTo(42);
            assertThat(result.bookingOpen()).isTrue();
            assertThat(result.bookingUrl()).isEqualTo("https://www.cgv.co.kr/reservation/?movNo=1234&siteNo=0059");
        }
    }
}
