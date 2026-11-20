package domain.movie.service;

import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class MovieServiceImpl implements MovieService {

    private static final String CGV = "CGV";
    private static final int BATCH_SIZE = 500;

    /**
     * 상영 슬롯(uk_schedules_slot) 기준 upsert.
     * 스케줄 PK 가 IDENTITY 라 Hibernate 는 JDBC batch insert 를 하지 못하므로 JdbcTemplate 으로 직접 배치 실행한다.
     */
    private static final String UPSERT_SCHEDULE_SQL = """
            INSERT INTO schedules (movie_id, theater_id, screen_type_id, show_date, show_time, is_opened, crawled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE is_opened = VALUES(is_opened), crawled_at = VALUES(crawled_at)
            """;

    private final MovieRepository movieRepository;
    private final TheaterRepository theaterRepository;
    private final ScreenTypeRepository screenTypeRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    @Override
    public Movie findOrCreateMovie(String title) {
        return movieRepository.findByTitle(title)
                .orElseGet(() -> movieRepository.save(
                        Movie.builder().movieId(title).title(title).build()));
    }

    @Override
    public Theater findOrCreateTheater(String brand, String branchName) {
        return theaterRepository.findByBrandAndBranchName(brand, branchName)
                .orElseGet(() -> theaterRepository.save(Theater.builder()
                        .theaterId(brand + "|" + branchName)
                        .brand(brand).branchName(branchName).build()));
    }

    @Override
    public ScreenType findOrCreateScreenType(String typeName) {
        return screenTypeRepository.findByTypeName(typeName)
                .orElseGet(() -> screenTypeRepository.save(
                        ScreenType.builder().typeName(typeName).build()));
    }

    @Override
    public void saveScreenings(List<ScreeningResult> results) {
        if (results.isEmpty()) return;

        // 1) 참조 데이터: 중복 제거한 키로 IN 조회 1회씩, 없는 것만 생성
        Map<String, Movie> movies = findOrCreateMovies(results);
        Map<String, Theater> theaters = findOrCreateTheaters(results);
        Map<String, ScreenType> screenTypes = findOrCreateScreenTypes(results);

        // 2) 새로 만든 영화/극장을 DB 에 반영 (아래 JDBC INSERT 의 FK 대상)
        entityManager.flush();

        // 3) 스케줄: 배치 upsert
        LocalDateTime crawledAt = LocalDateTime.now();
        jdbcTemplate.batchUpdate(UPSERT_SCHEDULE_SQL, results, BATCH_SIZE, (ps, r) -> {
            ps.setString(1, movies.get(r.movieTitle()).getMovieId());
            ps.setString(2, theaters.get(branchNameOf(r)).getTheaterId());
            ps.setInt(3, screenTypes.get(ScreenType.extractFrom(r.theater())).getScreenTypeId());
            ps.setObject(4, r.date(), Types.DATE);
            ps.setObject(5, r.time(), Types.TIME);
            ps.setBoolean(6, r.bookingOpen());
            ps.setObject(7, crawledAt, Types.TIMESTAMP);
        });
    }

    // ── private ───────────────────────────────────────────────

    private Map<String, Movie> findOrCreateMovies(List<ScreeningResult> results) {
        Set<String> titles = results.stream().map(ScreeningResult::movieTitle).collect(Collectors.toSet());
        Map<String, Movie> found = movieRepository.findAllByTitleIn(titles).stream()
                .collect(Collectors.toMap(Movie::getTitle, Function.identity()));

        List<Movie> created = titles.stream()
                .filter(title -> !found.containsKey(title))
                .map(title -> Movie.builder().movieId(title).title(title).build())
                .toList();
        created.forEach(m -> {
            entityManager.persist(m);   // id 를 직접 지정하는 엔티티라 save()는 merge → 불필요한 SELECT 발생
            found.put(m.getTitle(), m);
        });
        return found;
    }

    /** 극장은 결과의 상영관명("CGV 영등포타임스퀘어 SCREENX관")에서 지점명을 뽑아 식별한다. */
    private Map<String, Theater> findOrCreateTheaters(List<ScreeningResult> results) {
        // 지점명 → 해당 지점의 siteNo (bookingUrl 에서 파싱한 첫 값)
        Map<String, String> siteNoByBranch = new LinkedHashMap<>();
        for (ScreeningResult r : results) {
            siteNoByBranch.merge(branchNameOf(r), nullToEmpty(extractSiteNo(r.bookingUrl())),
                    (prev, next) -> prev.isEmpty() ? next : prev);
        }

        Map<String, Theater> found = theaterRepository.findAllByBrandAndBranchNameIn(CGV, siteNoByBranch.keySet())
                .stream().collect(Collectors.toMap(Theater::getBranchName, Function.identity()));

        List<Theater> created = new ArrayList<>();
        siteNoByBranch.forEach((branch, siteNo) -> {
            Theater theater = found.get(branch);
            if (theater == null) {
                created.add(Theater.builder()
                        .theaterId(CGV + "|" + branch).brand(CGV).branchName(branch)
                        .siteNo(emptyToNull(siteNo)).build());
            } else if (theater.getSiteNo() == null && !siteNo.isEmpty()) {
                theater.setSiteNo(siteNo);   // 변경 감지로 갱신
            }
        });
        created.forEach(t -> {
            entityManager.persist(t);
            found.put(t.getBranchName(), t);
        });
        return found;
    }

    private Map<String, ScreenType> findOrCreateScreenTypes(List<ScreeningResult> results) {
        Set<String> typeNames = results.stream()
                .map(r -> ScreenType.extractFrom(r.theater())).collect(Collectors.toSet());
        Map<String, ScreenType> found = screenTypeRepository.findAllByTypeNameIn(typeNames).stream()
                .collect(Collectors.toMap(ScreenType::getTypeName, Function.identity()));

        List<ScreenType> created = typeNames.stream()
                .filter(name -> !found.containsKey(name))
                .map(name -> ScreenType.builder().typeName(name).build())
                .toList();
        screenTypeRepository.saveAll(created).forEach(t -> found.put(t.getTypeName(), t));
        return found;
    }

    /** "CGV 영등포타임스퀘어 SCREENX관..." → "영등포타임스퀘어" */
    private static String branchNameOf(ScreeningResult result) {
        return result.theater().replace("CGV ", "").split(" ")[0];
    }

    private static String extractSiteNo(String bookingUrl) {
        if (bookingUrl == null) return null;
        int idx = bookingUrl.indexOf("siteNo=");
        if (idx < 0) return null;
        String after = bookingUrl.substring(idx + 7);
        int end = after.indexOf('&');
        return end < 0 ? after : after.substring(0, end);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
