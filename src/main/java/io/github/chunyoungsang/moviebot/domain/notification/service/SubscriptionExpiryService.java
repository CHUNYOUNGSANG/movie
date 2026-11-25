package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.notification.sender.AlertMessageFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 상영일이 지나도록 예매가 열리지 않은 신청을 만료 처리한다.
 * 크롤링 대상에서는 이미 빠지지만(findPending), 사용자는 신청이 끝났는지 알 수 없고 /list 에 계속 "대기 중"으로 남는다.
 * → 만료 시각을 기록하고, 구독 중인 사용자에게는 안내를 한 번 보낸다 (Outbox 로 발송, 상태 기록과 같은 트랜잭션).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionExpiryService {

    private final NotificationSubscriptionRepository subscriptionRepository;
    private final OutboxService outboxService;
    private final AlertMessageFormatter alertMessageFormatter;
    private final Clock clock;

    /** @return 이번에 만료 처리한 신청 수 */
    @Transactional
    public int expirePast() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        int expired = 0;
        for (NotificationSubscription sub : subscriptionRepository.findExpirable(today)) {
            // 조건부 UPDATE 로 선점: 그 사이 매칭됐거나 다른 실행이 이미 처리했으면 건너뜀 (안내 중복 방지)
            if (subscriptionRepository.claimExpired(sub.getSubscriptionId(), now) == 0) continue;
            expired++;
            if (sub.getUser().isActive()) {   // 구독을 해제한 사용자에게는 안내하지 않는다
                outboxService.enqueue(sub, alertMessageFormatter.formatExpired(sub));
            }
        }
        if (expired > 0) log.info("상영일이 지난 알림 신청 {}건 만료", expired);
        return expired;
    }
}
