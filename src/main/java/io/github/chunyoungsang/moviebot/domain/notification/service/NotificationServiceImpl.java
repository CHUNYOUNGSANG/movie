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
