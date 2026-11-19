package domain.notification.service;

import domain.movie.dto.ScreeningResult;
import domain.notification.entity.NotificationHistory;
import domain.notification.event.NotificationMatchedEvent;
import domain.notification.entity.NotificationSubscription;
import domain.notification.repository.NotificationHistoryRepository;
import domain.notification.repository.NotificationSubscriptionRepository;
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
    private final NotificationHistoryRepository historyRepository;
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
     * 트랜잭션 안에서 구독을 다시 조회하므로, 그 사이 다른 주기에서 이미 처리된 구독은 건너뛴다.
     */
    public void markMatched(List<Long> subscriptionIds, List<ScreeningResult> results) {
        if (results.isEmpty()) return;

        for (NotificationSubscription sub : subscriptionRepository.findAllBySubscriptionIdIn(subscriptionIds)) {
            if (sub.isTriggered()) continue;

            List<ScreeningResult> matched = results.stream().filter(sub::matches).toList();
            if (matched.isEmpty()) continue;

            sub.setTriggered(true);
            historyRepository.save(NotificationHistory.builder().subscription(sub).status("SUCCESS").build());
            // 발송은 커밋 이후 NotificationSendListener 에서 수행
            eventPublisher.publishEvent(
                    new NotificationMatchedEvent(sub.getSubscriptionId(), sub.getUser().getChatId(), matched));
        }
    }
}
