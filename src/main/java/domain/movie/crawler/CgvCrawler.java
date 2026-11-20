package domain.movie.crawler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.RequestOptions;
import domain.movie.dto.ScreeningResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Playwright 기반 CGV 크롤러.
 * 헤드리스 Chromium → Cloudflare 자동 통과 → CGV 내부 API 호출.
 * 스레드마다 브라우저 세션을 따로 두어 여러 그룹을 동시에 크롤링할 수 있다 (crawlExecutor).
 */
@Slf4j
@Component
public class CgvCrawler implements MovieCrawler {

    private static final String BASE    = "https://cgv.co.kr/api/v1/booking";
    private static final String CO_CD   = "A420";
    private static final DateTimeFormatter YMD  = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    // 극장명 → siteNo (Network 탭에서 확인된 값)
    static final Map<String, String> THEATER_SITE_NO = new LinkedHashMap<>(Map.of(
            "영등포", "0059",
            "영등포타임스퀘어", "0059",
            "용산", "0013",
            "용산아이파크몰", "0013",
            "강남", "0056",
            "홍대", "0212",
            "신촌", "0086",
            "왕십리", "0215",
            "건대입구", "0208",
            "여의도", "0219"
    ));

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 크롤링 스레드마다 자기 브라우저 세션을 가진다 (Playwright 는 만든 스레드에서만 사용 가능).
     * 처음 크롤링할 때 만들어지고, 크롤링 스레드풀의 스레드가 살아 있는 동안 재사용된다.
     */
    private final PerThreadResource<Session> sessions;

    public CgvCrawler() {
        this(Session::open);
    }

    /** 테스트에서 실제 브라우저 대신 가짜 세션을 넣기 위한 생성자 */
    CgvCrawler(Supplier<Session> sessionFactory) {
        this.sessions = new PerThreadResource<>(sessionFactory);
    }

    /** 스레드 하나가 쓰는 Playwright + 브라우저 + 컨텍스트 + Cloudflare 인증 상태 */
    static final class Session implements AutoCloseable {
        private final Playwright playwright;
        private final Browser browser;
        private final BrowserContext context;
        private boolean cfReady = false;

        Session(Playwright playwright, Browser browser, BrowserContext context) {
            this.playwright = playwright;
            this.browser = browser;
            this.context = context;
        }

        static Session open() {
            Playwright playwright = Playwright.create();
            try {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--no-sandbox", "--disable-setuid-sandbox",
                                "--disable-dev-shm-usage", "--disable-gpu")));
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"));
                log.info("Playwright 브라우저 세션 생성 ({})", Thread.currentThread().getName());
                return new Session(playwright, browser, context);
            } catch (RuntimeException e) {
                playwright.close();
                throw e;
            }
        }

        @Override
        public void close() {
            try { context.close(); } catch (Exception ignored) {}
            try { browser.close(); } catch (Exception ignored) {}
            playwright.close();
        }
    }

    @PreDestroy
    public void cleanup() {
        sessions.closeAll();
    }

    @Override
    public String getCinemaName() { return "CGV"; }

    @Override
    public List<ScreeningResult> crawl(String movieTitle, LocalDate date) {
        return crawl(movieTitle, null, date);
    }

    @Override
    public List<ScreeningResult> crawl(String movieTitle, String siteHint, LocalDate date) {
        Session session;
        try {
            session = sessions.get();
        } catch (Exception e) {
            log.error("Playwright 브라우저 세션 생성 실패: {}", e.getMessage());
            return List.of();
        }
        try {
            ensureCfReady(session);

            Optional<String> movNo = findMovNo(session, movieTitle);
            if (movNo.isEmpty()) {
                log.warn("CGV: 영화를 찾을 수 없습니다 [{}]", movieTitle);
                return List.of();
            }

            List<String> siteNos = resolveSiteNos(siteHint);
            List<ScreeningResult> results = new ArrayList<>();

            for (String siteNo : siteNos) {
                results.addAll(searchSchedules(session, movNo.get(), siteNo, date));
            }

            log.info("CGV: [{}] siteHint={} {} → {}건", movieTitle, siteHint, date, results.size());
            return results;

        } catch (Exception e) {
            log.error("CGV 크롤링 실패: {}", e.getMessage());
            session.cfReady = false; // 다음 호출 시 CF 재인증
            return List.of();
        }
    }

    // ── CF 인증 ────────────────────────────────────────────────

    private void ensureCfReady(Session session) {
        if (session.cfReady) return;
        try {
            Page page = session.context.newPage();
            page.navigate("https://www.cgv.co.kr");
            page.waitForLoadState();
            page.close();
            session.cfReady = true;
            log.info("CGV Cloudflare 인증 완료");
        } catch (Exception e) {
            log.warn("CGV CF 인증 실패 (계속 시도): {}", e.getMessage());
        }
    }

    // ── 영화 검색 ──────────────────────────────────────────────

    private Optional<String> findMovNo(Session session, String movieTitle) {
        try {
            String url = BASE + "/searchAtktTopPostrList?coCd=" + CO_CD
                    + "&movNm=" + URLEncoder.encode(movieTitle, StandardCharsets.UTF_8)
                    + "&div=&attrCd=";

            String body = getJson(session, url);
            if (body == null) return Optional.empty();

            JsonNode root = objectMapper.readTree(body);
            if (root.path("statusCode").asInt(-1) != 0) return Optional.empty();

            JsonNode data = root.path("data");
            String prefix = movieTitle.substring(0, Math.min(2, movieTitle.length()));

            for (JsonNode movie : data) {
                String nm = movie.path("movNm").asText("");
                if (nm.contains(prefix)) {
                    return Optional.of(movie.path("movNo").asText());
                }
            }
        } catch (Exception e) {
            log.error("영화 검색 실패 [{}]: {}", movieTitle, e.getMessage());
        }
        return Optional.empty();
    }

    // ── 상영 스케줄 조회 ────────────────────────────────────────

    private List<ScreeningResult> searchSchedules(Session session, String movNo, String siteNo, LocalDate date) {
        try {
            String url = BASE + "/searchSchByMov?coCd=" + CO_CD
                    + "&siteNo=" + siteNo
                    + "&scnYmd=" + date.format(YMD)
                    + "&movNo=" + movNo
                    + "&rtctlScopCd=08";

            String body = getJson(session, url);
            if (body == null) return List.of();

            JsonNode root = objectMapper.readTree(body);
            if (root.path("statusCode").asInt(-1) != 0) return List.of();

            List<ScreeningResult> results = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                results.add(toResult(item));
            }
            return results;

        } catch (Exception e) {
            log.debug("스케줄 조회 실패 (movNo={}, siteNo={}): {}", movNo, siteNo, e.getMessage());
            return List.of();
        }
    }

    private ScreeningResult toResult(JsonNode item) {
        String siteNm  = item.path("siteNm").asText();   // "CGV 영등포타임스퀘어"
        String scnsNm  = item.path("scnsNm").asText();   // "SCREENX관 (리클라이너)"
        String movNm   = item.path("movNm").asText();    // "스파이더맨-브랜드 뉴 데이"
        String scnYmd  = item.path("scnYmd").asText();   // "20260907"
        String startTm = item.path("scnsrtTm").asText(); // "0840"
        int    seats   = item.path("frSeatCnt").asInt(-1);
        String movNo   = item.path("movNo").asText();
        String siteNo  = item.path("siteNo").asText();

        LocalDate date = LocalDate.parse(scnYmd, YMD);
        LocalTime time = parseTime(startTm);

        // theater 필드에 극장명+관명 합침 → matches() 체크용
        String theater = siteNm + " " + scnsNm;
        String bookingUrl = "https://www.cgv.co.kr/reservation/?movNo=" + movNo + "&siteNo=" + siteNo;

        return new ScreeningResult("CGV", theater, movNm, date, time, seats, true, bookingUrl);
    }

    // ── HTTP ──────────────────────────────────────────────────

    private String getJson(Session session, String url) {
        try {
            APIResponse response = session.context.request().get(url,
                    RequestOptions.create()
                            .setHeader("Accept", "application/json")
                            .setHeader("Referer", "https://www.cgv.co.kr/"));
            if (!response.ok()) {
                log.warn("CGV API 오류 {}: {}", response.status(), url);
                return null;
            }
            return response.text();
        } catch (Exception e) {
            log.error("CGV API 호출 실패 {}: {}", url, e.getMessage());
            session.cfReady = false;
            return null;
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────

    private List<String> resolveSiteNos(String siteHint) {
        if (siteHint != null) {
            String found = THEATER_SITE_NO.entrySet().stream()
                    .filter(e -> siteHint.contains(e.getKey()) || e.getKey().contains(siteHint))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (found != null) return List.of(found);
            log.warn("CGV: 알 수 없는 극장 [{}] → 전체 조회", siteHint);
        }
        return new ArrayList<>(new LinkedHashSet<>(THEATER_SITE_NO.values()));
    }

    private LocalTime parseTime(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return null;
        try {
            String padded = hhmm.length() == 3 ? "0" + hhmm : hhmm;
            return LocalTime.parse(padded, HHMM);
        } catch (Exception e) { return null; }
    }
}