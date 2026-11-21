package io.github.chunyoungsang.moviebot.domain.notification.service;

import io.github.chunyoungsang.moviebot.domain.movie.dto.ScreeningResult;
import io.github.chunyoungsang.moviebot.domain.notification.event.NotificationMatchedEvent;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.NotificationOutbox;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.NotificationOutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationSubscriptionRepository;
import io.github.chunyoungsang.moviebot.domain.notification.sender.AlertMessageFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 구독의 상태 변경만 담당하는 짧은 트랜잭션 모음.
 * 크롤링 같은 외부 I/O 는 이 클래스 밖(트랜잭션 밖)에서 수행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationStateService {

    private final NotificationSubscriptionRepository subscriptionRepository;
    private final NotificationOutboxService outboxService;
    private final AlertMessageFormatter alertMessageFormatter;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 미발송 구독 조회.
     * 인터페이스에 선언한 쿼리 메서드는 Spring Data 가 트랜잭션을 걸지 않으므로,
     * 트랜잭션 밖에서 직접 호출하면 EntityManager 가 호출자 범위가 끝날 때까지 커넥션을 쥐고 있게 된다.
     * 명시적인 읽기 전용 트랜잭션으로 감싸 조회 직후 커넥션을 반납한다.
     */
    @Transactional(readOnly = true)
    public List<NotificationSubscription> findPending() {
        return subscriptionRepository.findAllByTriggeredFalse();
    }

    /**
     * 크롤링 결과와 매칭되는 구독을 발송 처리한다.
     * 엔티티의 triggered 값(읽은 시점의 스냅샷)이 아니라 조건부 UPDATE 결과로 선점 여부를 판단한다.
     * → 다른 서버가 같은 구독을 동시에 처리해도 한 곳에서만 Outbox 가 생성된다.
     */
    public void markMatched(List<Long> subscriptionIds, List<ScreeningResult> results) {
        if (results.isEmpty()) return;

        for (NotificationSubscription sub : subscriptionRepository.findAllBySubscriptionIdIn(subscriptionIds)) {
            if (sub.isTriggered()) continue;

            List<ScreeningResult> matched = results.stream().filter(sub::matches).toList();
            if (matched.isEmpty()) continue;

            // 선점 실패 = 다른 서버(또는 다른 주기)가 이미 처리함
            if (subscriptionRepository.claimTriggered(sub.getSubscriptionId()) == 0) continue;

            // 구독 상태 변경과 "보낼 메시지" 기록을 같은 트랜잭션으로 커밋 (Transactional Outbox)
            NotificationOutbox outbox = outboxService.enqueue(sub, alertMessageFormatter.format(matched));
            // 커밋 이후 즉시 발송 시도 (실패/누락 시 relay 가 재시도)
            eventPublisher.publishEvent(new NotificationMatchedEvent(outbox.getOutboxId()));
        }
    }
}
