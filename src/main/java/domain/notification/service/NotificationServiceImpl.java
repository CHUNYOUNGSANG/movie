package domain.notification.service;

import domain.movie.crawler.MovieCrawler;
import domain.movie.dto.ScreeningResult;
import domain.movie.entity.Movie;
import domain.movie.entity.ScreenType;
import domain.movie.entity.Theater;
import domain.movie.service.MovieService;
import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationSubscription;
import domain.notification.repository.NotificationSubscriptionRepository;
import domain.user.entity.User;
import domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import domain.notification.outbox.NotificationOutboxService;
import domain.notification.outbox.OutboxStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationSubscriptionRepository notificationSubscriptionRepository;
    private final MovieService movieService;     // SRP: 영화/극장/상영관/스케줄 관리는 Movie 도메인 책임
    private final UserService userService;       // SRP: 사용자 조회/구독 상태 관리는 User 도메인 책임
    private final NotificationStateService notificationStateService;
    private final NotificationOutboxService outboxService;
    private final List<MovieCrawler> crawlers;   // OCP: 새 크롤러 추가 시 이 코드 수정 없음

    @Override
    public NotificationSubscription register(NotificationRegisterRequest req) {
        User user = userService.getByChatIdOrThrow(req.chatId());

        Movie movie = movieService.findOrCreateMovie(req.movieTitle());

        Theater theater = null;
        if (req.branchName() != null && !req.branchName().isBlank()) {
            String brand = req.brand() != null ? req.brand() : "CGV";
            theater = movieService.findOrCreateTheater(brand, req.branchName());
        }

        ScreenType screenType = null;
        if (req.screenTypeName() != null && !req.screenTypeName().isBlank()) {
            screenType = movieService.findOrCreateScreenType(req.screenTypeName());
        }

        return notificationSubscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).theater(theater).screenType(screenType)
                .targetDate(req.targetDate()).build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationSubscription> findAll() {
        return notificationSubscriptionRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationSubscription> findByUser(User user) {
        return notificationSubscriptionRepository.findByUser(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, OutboxStatus> findDeliveryStatuses(List<Long> subscriptionIds) {
        return outboxService.findLatestStatuses(subscriptionIds);
    }

    @Override
    public void cancel(Long subscriptionId, String chatId) {
        NotificationSubscription sub = notificationSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("알림 신청을 찾을 수 없습니다: " + subscriptionId));
        if (!sub.getUser().getChatId().equals(chatId)) {
            throw new SecurityException("본인이 신청한 알림만 취소할 수 있습니다.");
        }
        notificationSubscriptionRepository.delete(sub);
    }

    /**
     * 크롤링(외부 I/O, 수 초 소요)은 트랜잭션 밖에서 수행하고,
     * DB 반영은 MovieService / NotificationStateService 의 짧은 트랜잭션으로 나눈다.
     * → 크롤링 동안 DB 커넥션을 점유하지 않고, 한 그룹의 실패가 이미 처리된 그룹을 롤백시키지 않는다.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void checkAndNotify() {
        List<NotificationSubscription> pending = notificationStateService.findPending();
        if (pending.isEmpty()) {
            log.info("대기 중인 알림 신청 없음");
            return;
        }
        log.info("알림 신청 {}건 확인 시작", pending.size());

        // movieId|branchName|targetDate 기준으로 그룹핑 (같은 조건은 한 번만 크롤링)
        Map<String, List<NotificationSubscription>> groups = pending.stream()
                .collect(Collectors.groupingBy(s -> {
                    String branch = s.getTheater() != null ? s.getTheater().getBranchName() : "";
                    return s.getMovie().getMovieId() + "|" + branch + "|" + s.getTargetDate();
                }));

        for (List<NotificationSubscription> group : groups.values()) {
            NotificationSubscription sample = group.get(0);
            String siteHint = sample.getTheater() != null ? sample.getTheater().getBranchName() : null;
            List<Long> subscriptionIds = group.stream().map(NotificationSubscription::getSubscriptionId).toList();
            crawlers.forEach(crawler ->
                    crawlAndMatch(crawler, sample.getMovie().getTitle(),
                            siteHint, sample.getTargetDate(), subscriptionIds));
        }
    }

    // ── private ───────────────────────────────────────────────

    private void crawlAndMatch(MovieCrawler crawler, String movieTitle,
                               String siteNo, LocalDate targetDate, List<Long> subscriptionIds) {
        // 1) 크롤링 — 트랜잭션 없음
        List<ScreeningResult> results = crawler.crawl(movieTitle, siteNo, targetDate);
        log.info("[{}] {} siteNo={} {} → {}건", crawler.getCinemaName(), movieTitle, siteNo, targetDate, results.size());
        if (results.isEmpty()) return;

        // 2) 스케줄 저장 — 짧은 트랜잭션
        movieService.saveScreenings(results);

        // 3) 구독 매칭 및 발송 처리 — 짧은 트랜잭션
        notificationStateService.markMatched(subscriptionIds, results);
    }
}