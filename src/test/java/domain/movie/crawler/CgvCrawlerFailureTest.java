package domain.movie.crawler;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import domain.movie.dto.ScreeningResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 크롤러 오류 재현: CgvCrawler 는 조회 자체가 실패해도 예외 대신 빈 결과를 돌려준다.
 * 호출하는 쪽(지표 포함)은 "상영 정보 없음"과 "조회 실패"를 구분할 수 없다.
 *
 * 실제 브라우저/CGV 대신 Playwright 인터페이스를 가짜로 만들어, CGV API 응답을 상황별로 흉내 낸다.
 */
@DisplayName("CgvCrawler 오류 재현")
class CgvCrawlerFailureTest {

    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);
    private static final int ALL_SITES = new LinkedHashSet<>(CgvCrawler.THEATER_SITE_NO.values()).size();

    // ── 조회 실패: 빈 결과가 아니라 실패로 알려야 한다 ──────────────────

    @Test
    @DisplayName("[브라우저 실행 실패] Chromium 을 띄우지 못하면 실패로 알려야 한다")
    void browserLaunchFailureIsReported() {
        CgvCrawler crawler = new CgvCrawler(() -> {
            throw new IllegalStateException("Executable doesn't exist (테스트)");
        });

        assertReportedAsFailure("브라우저 실행 실패", () -> crawler.crawl("위키드", null, DATE));
    }

    @Test
    @DisplayName("[Cloudflare 차단] 모든 API 가 403 이면 '영화를 찾을 수 없음'이 아니라 실패로 알려야 한다")
    void blockedByCloudflareIsReported() {
        CgvCrawler crawler = crawlerWith(url -> new Reply(403, "<html>Just a moment...</html>"));

        assertReportedAsFailure("Cloudflare 차단(403)", () -> crawler.crawl("위키드", null, DATE));
    }

    @Test
    @DisplayName("[스케줄 API 장애] 영화 검색은 되지만 모든 극장의 스케줄 조회가 실패하면 실패로 알려야 한다")
    void allScheduleRequestsFailingIsReported() {
        CgvCrawler crawler = crawlerWith(url -> url.contains("searchAtktTopPostrList")
                ? new Reply(200, MOVIE_FOUND)
                : new Reply(502, "Bad Gateway"));

        assertReportedAsFailure("스케줄 API 502 (극장 " + ALL_SITES + "곳 전부)", () -> crawler.crawl("위키드", null, DATE));
    }

    // ── 정상 동작: 지금도 맞고, 바뀌면 안 된다 ──────────────────────────

    @Test
    @DisplayName("[상영 없음] 검색 결과에 영화가 없는 것은 실패가 아니라 빈 결과다")
    void movieNotListedIsEmptyNotFailure() {
        CgvCrawler crawler = crawlerWith(url -> new Reply(200, "{\"statusCode\":0,\"data\":[]}"));

        List<ScreeningResult> results = crawler.crawl("위키드", null, DATE);

        System.out.printf("[크롤러 오류] %-30s → 빈 결과 %d건%n", "상영 정보 없음 (정상)", results.size());
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("[정상] 영화 검색과 스케줄 조회가 성공하면 결과를 돌려준다")
    void returnsResultsWhenApisSucceed() {
        CgvCrawler crawler = crawlerWith(url -> url.contains("searchAtktTopPostrList")
                ? new Reply(200, MOVIE_FOUND)
                : new Reply(200, schedule(siteNoOf(url))));

        List<ScreeningResult> results = crawler.crawl("위키드", "강남", DATE);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).movieTitle()).isEqualTo("위키드");
    }

    // ── helpers ───────────────────────────────────────────────

    static final String MOVIE_FOUND = "{\"statusCode\":0,\"data\":[{\"movNm\":\"위키드\",\"movNo\":\"30000001\"}]}";

    static String schedule(String siteNo) {
        return """
                {"statusCode":0,"data":[{"siteNm":"CGV %s","scnsNm":"1관","movNm":"위키드","scnYmd":"20261224",
                 "scnsrtTm":"1900","frSeatCnt":100,"movNo":"30000001","siteNo":"%s"}]}
                """.formatted(siteNo, siteNo);
    }

    static String siteNoOf(String url) {
        String rest = url.substring(url.indexOf("siteNo=") + 7);
        return rest.contains("&") ? rest.substring(0, rest.indexOf('&')) : rest;
    }

    private static void assertReportedAsFailure(String situation, Supplier<List<ScreeningResult>> crawl) {
        List<ScreeningResult> results = null;
        RuntimeException error = null;
        try {
            results = crawl.get();
        } catch (RuntimeException e) {
            error = e;
        }
        System.out.printf("[크롤러 오류] %-30s → %s%n", situation,
                error != null ? "실패로 알림 (" + error.getClass().getSimpleName() + ")" : "빈 결과 " + results.size() + "건");
        assertThat(error).as(situation + ": 빈 결과가 아니라 실패로 알려야 함").isNotNull();
    }

    record Reply(int status, String body) {
    }

    /** CGV API 를 흉내 내는 가짜 Playwright 세션 */
    static CgvCrawler crawlerWith(Function<String, Reply> cgv) {
        return new CgvCrawler(() -> fakeSession(cgv));
    }

    static CgvCrawler.Session fakeSession(Function<String, Reply> cgv) {
        BrowserContext context = mock(BrowserContext.class);
        APIRequestContext request = mock(APIRequestContext.class);
        given(context.newPage()).willReturn(mock(Page.class));   // Cloudflare 통과용 페이지 방문
        given(context.request()).willReturn(request);
        given(request.get(anyString(), any(RequestOptions.class))).willAnswer(inv -> {
            Reply reply = cgv.apply(inv.getArgument(0));
            APIResponse response = mock(APIResponse.class);
            given(response.status()).willReturn(reply.status());
            given(response.ok()).willReturn(reply.status() >= 200 && reply.status() < 300);
            given(response.text()).willReturn(reply.body());
            return response;
        });
        return new CgvCrawler.Session(mock(Playwright.class), mock(Browser.class), context);
    }
}
