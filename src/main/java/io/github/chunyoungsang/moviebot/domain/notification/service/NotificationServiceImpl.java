package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.notification.exception.NotificationErrorCode;
import io.github.chunyoungsang.moviebot.global.exception.BusinessException;

import io.github.chunyoungsang.moviebot.domain.movie.entity.Movie;
import io.github.chunyoungsang.moviebot.domain.movie.entity.ScreenType;
import io.github.chunyoungsang.moviebot.domain.movie.entity.Theater;
import io.github.chunyoungsang.moviebot.domain.movie.service.MovieService;
import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationRegisterRequest;
import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationResponse;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.DeliveryStatus;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.user.entity.User;
import io.github.chunyoungsang.moviebot.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 알림 신청 관리 (신청·조회·취소·발송 상태). 예매 오픈 확인(크롤링 → 매칭)은 BookingCheckService */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationSubscriptionRepository notificationSubscriptionRepository;
    private final MovieService movieService;     // SRP: 영화/극장/상영관/스케줄 관리는 Movie 도메인 책임
    private final UserService userService;       // SRP: 사용자 조회/구독 상태 관리는 User 도메인 책임
    private final OutboxService outboxService;
    private final Clock clock;

    /** 영화 제목 최대 길이 (movies.movie_id 에 제목을 그대로 쓴다) */
    static final int MAX_TITLE_LENGTH = 100;

    @Override
    public NotificationSubscription register(NotificationRegisterRequest req) {
        // 봇·API 공통 규칙이므로 채널(컨트롤러·명령어)이 아니라 여기서 검증한다
        String title = req.movieTitle() == null ? "" : req.movieTitle().strip();
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH || req.targetDate() == null) {
            throw new BusinessException(NotificationErrorCode.INVALID_SUBSCRIPTION_REQUEST,
                    "title=" + req.movieTitle() + ", date=" + req.targetDate());
        }
        if (req.targetDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(NotificationErrorCode.TARGET_DATE_PAST, "date=" + req.targetDate());
        }

        User user = userService.getByChatIdOrThrow(req.chatId());

        Movie movie = movieService.findOrCreateMovie(title);

        Theater theater = null;
        if (req.branchName() != null && !req.branchName().isBlank()) {
            String brand = req.brand() != null ? req.brand() : "CGV";
            theater = movieService.findOrCreateTheater(brand, req.branchName());
        }

        ScreenType screenType = null;
        if (req.screenTypeName() != null && !req.screenTypeName().isBlank()) {
            screenType = movieService.findOrCreateScreenType(req.screenTypeName());
        }

        // 같은 사용자의 대기 중인 같은 조건 신청 (알림이 두 번 가고 크롤링만 늘어난다). 위에서 만든 영화 등은 롤백된다
        if (notificationSubscriptionRepository.existsPendingDuplicate(user, movie, theater, screenType, req.targetDate())) {
            throw new BusinessException(NotificationErrorCode.DUPLICATE_SUBSCRIPTION,
                    "chatId=" + req.chatId() + ", movie=" + title + ", date=" + req.targetDate());
        }

        return notificationSubscriptionRepository.save(NotificationSubscription.builder()
                .user(user).movie(movie).theater(theater).screenType(screenType)
                .targetDate(req.targetDate()).build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> findByChatId(String chatId) {
        return userService.findByChatId(chatId)
                .map(notificationSubscriptionRepository::findByUser)
                .orElse(List.of())
                .stream().map(NotificationResponse::from).toList();   // 지연 로딩 연관(영화·극장)을 트랜잭션 안에서 읽는다
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationSubscription> findByUser(User user) {
        return notificationSubscriptionRepository.findByUser(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, DeliveryStatus> findDeliveryStatuses(List<Long> subscriptionIds) {
        return outboxService.findDeliveryStatuses(subscriptionIds);
    }

    @Override
    public void cancel(Long subscriptionId, String chatId) {
        NotificationSubscription sub = notificationSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.SUBSCRIPTION_NOT_FOUND, "id=" + subscriptionId));
        if (!sub.getUser().getChatId().equals(chatId)) {
            throw new BusinessException(NotificationErrorCode.SUBSCRIPTION_ACCESS_DENIED, "id=" + subscriptionId);
        }
        notificationSubscriptionRepository.delete(sub);
    }
}
