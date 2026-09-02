package domain.movie.service;

import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.MovieRegisterRequest;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.TargetMovie;
import domain.movie.repository.TargetMovieRepository;
import domain.telegram.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SRP : 영화 감시 비즈니스 로직만 담당.
 * DIP : MovieCrawler 인터페이스에만 의존 — 구현체(CGV/Megabox)를 직접 알지 못함.
 *       Spring이 List<MovieCrawler>에 모든 구현체를 자동 주입.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MovieServiceImpl implements MovieService {

    private final TargetMovieRepository targetMovieRepository;
    private final List<MovieCrawler> crawlers;      // OCP: 구현체 추가해도 이 코드 변경 없음
    private final TelegramService telegramService;

    /** 발송된 상영 키 세트. 재시작 시 초기화 (필요 시 DB 테이블로 교체). */
    private final Set<String> notifiedKeys = ConcurrentHashMap.newKeySet();

    @Override
    public TargetMovie register(MovieRegisterRequest request) {
        TargetMovie target = TargetMovie.builder()
                .title(request.title())
                .cinema(request.cinema() != null ? request.cinema().toUpperCase() : "ALL")
                .theater(request.theater())
                .targetDate(request.targetDate())
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
        return targetMovieRepository.save(target);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TargetMovie> findAll() {
        return targetMovieRepository.findAll();
    }

    @Override
    public void deactivate(Long id) {
        TargetMovie target = targetMovieRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("대상 영화를 찾을 수 없습니다: " + id));
        target.setActive(false);
    }

    /**
     * 스케줄러가 주기적으로 호출하는 핵심 메서드.
     * 크롤링은 I/O 작업이므로 트랜잭션 범위 밖에서 실행.
     */
    @Override
    public void checkAndNotify() {
        List<TargetMovie> targets = targetMovieRepository.findAllByActiveTrue();
        if (targets.isEmpty()) {
            log.info("감시 중인 영화 없음");
            return;
        }

        log.info("{}개 영화 감시 시작", targets.size());
        for (TargetMovie target : targets) {
            LocalDate date = target.getTargetDate() != null
                    ? target.getTargetDate() : LocalDate.now();

            selectCrawlers(target.getCinema())
                    .forEach(crawler -> crawlAndNotify(crawler, target.getTitle(), date, target.getTheater()));
        }
    }

    private void crawlAndNotify(MovieCrawler crawler, String title, LocalDate date, String theater) {
        List<ScreeningResult> results = crawler.crawl(title, date);
        for (ScreeningResult result : results) {
            if (theater != null && !result.theater().contains(theater)) continue;
            if (notifiedKeys.add(result.uniqueKey())) {
                telegramService.sendAlert(result);
                log.info("알림 발송: [{}] {} {} {}",
                        result.cinema(), result.movieTitle(), result.date(), result.time());
            }
        }
    }

    /** "ALL" → 전체 크롤러 / 그 외 → 이름 일치하는 크롤러만 */
    private List<MovieCrawler> selectCrawlers(String cinema) {
        if ("ALL".equalsIgnoreCase(cinema)) return crawlers;
        return crawlers.stream()
                .filter(c -> c.getCinemaName().equalsIgnoreCase(cinema))
                .toList();
    }
}