package domain.movie.service;

import domain.TestApplication;
import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Schedule;
import domain.movie.entity.Theater;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScheduleRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import support.MySqlTestContainer;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;
import support.QueryCounter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2순위 개선 과제 재현: 크롤링 결과 저장 쿼리 수가 결과 건수에 비례하는 문제.
 * 실제 JDBC 실행(DB 왕복) 횟수를 세어 검증한다.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "telegram.bot.token=test-token",
        "movie.check.cron=-"
})
@Import(QueryCounter.Config.class)
@DisplayName("스케줄 저장 벌크 처리 재현")
class ScheduleBulkSaveTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);   // 테스트 클래스마다 독립된 DB
    }

    /** 극장 5곳 × 상영관 2종 × 시간대 10개 = 100건 */
    private static final int RESULT_SIZE = 100;
    /** 결과 건수와 무관하게 일정해야 하는 쿼리 수 상한 */
    private static final int MAX_QUERIES = 15;
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);

    @MockitoBean private RestTemplate restTemplate;
    @MockitoBean private MovieCrawler crawler;

    @Autowired private MovieService movieService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private TheaterRepository theaterRepository;
    @Autowired private ScreenTypeRepository screenTypeRepository;
    @Autowired private ScheduleRepository scheduleRepository;

    @AfterEach
    void cleanUp() {
        scheduleRepository.deleteAllInBatch();
        theaterRepository.deleteAllInBatch();
        screenTypeRepository.deleteAllInBatch();
        movieRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[신규 저장] 크롤링 결과 100건을 처음 저장할 때 쿼리 수가 결과 건수에 비례하지 않아야 한다")
    void firstSaveQueryCountIsConstant() {
        List<ScreeningResult> results = screenings(true);

        QueryCounter.start();
        movieService.saveScreenings(results);
        int queries = QueryCounter.count();
        System.out.printf("[신규 저장] 결과 %d건 → 쿼리 %d회%n", RESULT_SIZE, queries);

        assertThat(scheduleRepository.count()).isEqualTo(RESULT_SIZE);
        assertThat(queries).as("결과 %d건 저장에 실행된 쿼리 수", RESULT_SIZE).isLessThanOrEqualTo(MAX_QUERIES);
    }

    @Test
    @DisplayName("[갱신] 이미 저장된 100건의 예매 오픈 여부를 갱신할 때도 쿼리 수가 일정해야 하고, 중복 행이 생기면 안 된다")
    void updateQueryCountIsConstant() {
        movieService.saveScreenings(screenings(false));   // 1주기: 예매 전

        QueryCounter.start();
        movieService.saveScreenings(screenings(true));    // 2주기: 예매 오픈
        int queries = QueryCounter.count();
        System.out.printf("[갱신] 결과 %d건 → 쿼리 %d회%n", RESULT_SIZE, queries);

        List<Schedule> schedules = scheduleRepository.findAll();
        assertThat(schedules).hasSize(RESULT_SIZE);
        assertThat(schedules).allMatch(Schedule::isOpened);
        assertThat(queries).as("결과 %d건 갱신에 실행된 쿼리 수", RESULT_SIZE).isLessThanOrEqualTo(MAX_QUERIES);
    }

    @Test
    @DisplayName("상영관명에서 지점명을, bookingUrl 에서 siteNo 를 파싱해 극장을 만든다")
    void createsTheaterFromResult() {
        movieService.saveScreenings(List.of(screening("CGV 영등포타임스퀘어 SCREENX관", "https://cgv.co.kr/ticket?siteNo=0059&x=1")));

        Theater theater = theaterRepository.findByBrandAndBranchName("CGV", "영등포타임스퀘어").orElseThrow();
        assertThat(theater.getTheaterId()).isEqualTo("CGV|영등포타임스퀘어");
        assertThat(theater.getSiteNo()).isEqualTo("0059");
        assertThat(screenTypeRepository.findByTypeName("ScreenX")).isPresent();
    }

    @Test
    @DisplayName("이미 있는 극장의 siteNo 가 비어 있으면 채우고, 있으면 덮어쓰지 않는다")
    void fillsSiteNoOnlyWhenEmpty() {
        theaterRepository.save(Theater.builder().theaterId("CGV|강남").brand("CGV").branchName("강남").build());
        theaterRepository.save(Theater.builder().theaterId("CGV|홍대").brand("CGV").branchName("홍대").siteNo("0191").build());

        movieService.saveScreenings(List.of(
                screening("CGV 강남 1관", "https://cgv.co.kr/ticket?siteNo=0056"),
                screening("CGV 홍대 1관", "https://cgv.co.kr/ticket?siteNo=9999")));

        assertThat(theaterRepository.findById("CGV|강남").orElseThrow().getSiteNo()).isEqualTo("0056");
        assertThat(theaterRepository.findById("CGV|홍대").orElseThrow().getSiteNo()).isEqualTo("0191");
    }

    private ScreeningResult screening(String theater, String bookingUrl) {
        return new ScreeningResult("CGV", theater, "위키드", DATE, LocalTime.of(19, 0), 100, true, bookingUrl);
    }

    private List<ScreeningResult> screenings(boolean bookingOpen) {
        String[] branches = {"강남", "영등포", "용산아이파크몰", "왕십리", "홍대"};
        String[] halls = {"1관", "IMAX관"};
        List<ScreeningResult> results = new ArrayList<>();
        for (String branch : branches) {
            for (String hall : halls) {
                for (int i = 0; i < 10; i++) {
                    results.add(new ScreeningResult("CGV", "CGV " + branch + " " + hall, "위키드",
                            DATE, LocalTime.of(9 + i, 0), 100, bookingOpen,
                            "https://cgv.co.kr/ticket?siteNo=00" + branch.length()));
                }
            }
        }
        assertThat(results).hasSize(RESULT_SIZE);
        return results;
    }
}
