package domain.notification.service;

import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.Schedule;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.repository.MovieRepository;
import domain.movie.repository.ScheduleRepository;
import domain.movie.repository.ScreenTypeRepository;
import domain.movie.repository.TheaterRepository;
import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationHistory;
import domain.notification.entity.NotificationRequest;
import domain.notification.repository.NotificationHistoryRepository;
import domain.notification.repository.NotificationRequestRepository;
import domain.telegram.service.MessageSender;
import domain.user.entity.User;
import domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRequestRepository notificationRequestRepository;
    private final NotificationHistoryRepository notificationHistoryRepository;
    private final MovieRepository movieRepository;
    private final TheaterRepository theaterRepository;
    private final ScreenTypeRepository screenTypeRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final MessageSender messageSender;   // DIP: 추상화에 의존 (TelegramService 직접 의존 X)
    private final List<MovieCrawler> crawlers;   // OCP: 새 크롤러 추가 시 이 코드 수정 없음

    @Override
    public NotificationRequest register(NotificationRegisterRequest req) {
        User user = userRepository.findByChatId(req.chatId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "등록된 사용자가 없습니다. /start 를 먼저 입력하세요."));

        Movie movie = findOrCreateMovie(req.movieTitle());

        Theater theater = null;
        if (req.branchName() != null && !req.branchName().isBlank()) {
            String brand = req.brand() != null ? req.brand() : "CGV";
            theater = findOrCreateTheater(brand, req.branchName());
        }

        ScreenType screenType = null;
        if (req.screenTypeName() != null && !req.screenTypeName().isBlank()) {
            screenType = findOrCreateScreenType(req.screenTypeName());
        }

        return notificationRequestRepository.save(NotificationRequest.builder()
                .user(user).movie(movie).theater(theater).screenType(screenType)
                .targetDate(req.targetDate()).build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationRequest> findAll() {
        return notificationRequestRepository.findAll();
    }

    @Override
    public void cancel(Long requestId) {
        NotificationRequest req = notificationRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("알림 신청을 찾을 수 없습니다: " + requestId));
        notificationRequestRepository.delete(req);
    }

    @Override
    public void checkAndNotify() {
        List<NotificationRequest> pending = notificationRequestRepository.findAllByTriggeredFalse();
        if (pending.isEmpty()) {
            log.info("대기 중인 알림 신청 없음");
            return;
        }
        log.info("알림 신청 {}건 확인 시작", pending.size());

        // movieId|branchName|targetDate 기준으로 그룹핑 (같은 조건은 한 번만 크롤링)
        Map<String, List<NotificationRequest>> groups = pending.stream()
                .collect(Collectors.groupingBy(r -> {
                    String branch = r.getTheater() != null ? r.getTheater().getBranchName() : "";
                    return r.getMovie().getMovieId() + "|" + branch + "|" + r.getTargetDate();
                }));

        for (List<NotificationRequest> group : groups.values()) {
            NotificationRequest sample = group.get(0);
            String siteHint = sample.getTheater() != null ? sample.getTheater().getBranchName() : null;
            crawlers.forEach(crawler ->
                    crawlAndMatch(crawler, sample.getMovie().getTitle(),
                            siteHint, sample.getTargetDate(), group));
        }
    }

    // ── private ───────────────────────────────────────────────

    private void crawlAndMatch(MovieCrawler crawler, String movieTitle,
                               String siteNo, LocalDate targetDate, List<NotificationRequest> group) {
        List<ScreeningResult> results = crawler.crawl(movieTitle, siteNo, targetDate);
        log.info("[{}] {} siteNo={} {} → {}건", crawler.getCinemaName(), movieTitle, siteNo, targetDate, results.size());

        // DB 저장 (스케줄 갱신)
        for (ScreeningResult result : results) {
            Movie movie     = findOrCreateMovie(result.movieTitle());
            Theater theater = findOrCreateTheaterFromResult(result);
            ScreenType st   = findOrCreateScreenType(ScreenType.extractFrom(result.theater()));
            saveOrUpdateSchedule(movie, theater, st, result);
        }

        // 알림: 요청별로 매칭되는 모든 시간대를 모아 한 번에 발송
        for (NotificationRequest req : group) {
            if (req.isTriggered()) continue;

            List<ScreeningResult> matched = results.stream()
                    .filter(req::matches)
                    .toList();

            if (!matched.isEmpty()) {
                notifyAll(req, matched);
            }
        }
    }

    private void notifyAll(NotificationRequest req, List<ScreeningResult> results) {
        req.setTriggered(true);
        notificationHistoryRepository.save(
                NotificationHistory.builder().request(req).status("SUCCESS").build());
        messageSender.sendAlerts(req.getUser().getChatId(), results);
        log.info("알림 발송: chatId={}, movie={}, {}개 시간대",
                req.getUser().getChatId(), results.get(0).movieTitle(), results.size());
    }

    private void saveOrUpdateSchedule(Movie movie, Theater theater,
                                      ScreenType screenType, ScreeningResult result) {
        scheduleRepository.findByMovieAndTheaterAndScreenTypeAndShowDateAndShowTime(
                        movie, theater, screenType, result.date(), result.time())
                .ifPresentOrElse(
                        s -> s.setOpened(result.bookingOpen()),
                        () -> scheduleRepository.save(Schedule.builder()
                                .movie(movie).theater(theater).screenType(screenType)
                                .showDate(result.date()).showTime(result.time())
                                .opened(result.bookingOpen()).build()));
    }

    private Movie findOrCreateMovie(String title) {
        return movieRepository.findByTitle(title)
                .orElseGet(() -> movieRepository.save(
                        Movie.builder().movieId(title).title(title).build()));
    }

    private Theater findOrCreateTheater(String brand, String branchName) {
        return theaterRepository.findByBrandAndBranchName(brand, branchName)
                .orElseGet(() -> theaterRepository.save(Theater.builder()
                        .theaterId(brand + "|" + branchName)
                        .brand(brand).branchName(branchName).build()));
    }

    // 크롤링 결과(siteNm 포함)로부터 Theater를 찾거나 생성. siteNo는 결과의 bookingUrl에서 파싱
    private Theater findOrCreateTheaterFromResult(ScreeningResult result) {
        String siteNm = result.theater();  // "CGV 영등포타임스퀘어 SCREENX관..."
        String branchName = siteNm.replace("CGV ", "").split(" ")[0]; // "영등포타임스퀘어"
        String siteNo = extractSiteNo(result.bookingUrl());

        return theaterRepository.findByBrandAndBranchName("CGV", branchName)
                .map(t -> {
                    if (t.getSiteNo() == null && siteNo != null) {
                        t.setSiteNo(siteNo);
                    }
                    return t;
                })
                .orElseGet(() -> theaterRepository.save(Theater.builder()
                        .theaterId("CGV|" + branchName)
                        .brand("CGV").branchName(branchName).siteNo(siteNo).build()));
    }

    private String extractSiteNo(String bookingUrl) {
        if (bookingUrl == null) return null;
        try {
            int idx = bookingUrl.indexOf("siteNo=");
            if (idx < 0) return null;
            String after = bookingUrl.substring(idx + 7);
            int end = after.indexOf('&');
            return end < 0 ? after : after.substring(0, end);
        } catch (Exception e) {
            return null;
        }
    }

    private ScreenType findOrCreateScreenType(String typeName) {
        return screenTypeRepository.findByTypeName(typeName)
                .orElseGet(() -> screenTypeRepository.save(
                        ScreenType.builder().typeName(typeName).build()));
    }
}
