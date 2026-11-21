package io.github.chunyoungsang.moviebot.domain.notification.outbox.service;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.NotificationOutbox;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.OutboxStatus;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.repository.NotificationOutboxRepository;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationHistory;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import lombok.RequiredArgsConstructor;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbox 상태 전이를 담당하는 짧은 트랜잭션 모음.
 * 실제 발송(외부 I/O)은 트랜잭션 밖의 NotificationDispatcher 가 수행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OutboxService {


    private final NotificationOutboxRepository outboxRepository;
    private final NotificationHistoryRepository historyRepository;
    private final OutboxProperties properties;
    private final RetryPolicy retryPolicy;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 보낼 알림을 기록하고, 커밋 이후 즉시 발송되도록 이벤트를 발행한다.
     * 호출한 쪽의 트랜잭션(구독 상태 변경)에 합류해 함께 커밋/롤백된다 (Transactional Outbox).
     * 즉시 발송이 실패하거나 실행되지 못해도 Outbox 에 남아 있으므로 relay 가 발송한다.
     */
    public void enqueue(NotificationSubscription sub, String message) {
        NotificationOutbox outbox = outboxRepository.save(NotificationOutbox.create(sub, message, LocalDateTime.now()));
        eventPublisher.publishEvent(new OutboxEnqueuedEvent(outbox.getOutboxId()));
    }

    /** 발송 선점. 다른 쪽이 이미 집었거나 아직 차례가 아니면 empty. */
    public Optional<OutboxMessage> claim(Long outboxId) {
        if (outboxRepository.claim(outboxId, LocalDateTime.now()) == 0) {
            return Optional.empty();
        }
        return outboxRepository.findById(outboxId)
                .map(o -> new OutboxMessage(o.getOutboxId(), o.getChatId(), o.getMessage()));
    }

    public void markSent(Long outboxId) {
        NotificationOutbox outbox = outboxRepository.findById(outboxId).orElseThrow();
        outbox.markSent();
        historyRepository.save(NotificationHistory.builder()
                .subscription(outbox.getSubscription()).status("SUCCESS").build());
    }

    /**
     * 발송 실패 처리. 재시도 여부와 다음 시도 시각은 RetryPolicy 가 정한다. 포기(DEAD)하면 FAIL 이력을 남긴다.
     *
     * @return 이번 실패를 어떻게 처리했는지 (RETRY / RATE_LIMITED / DEAD)
     */
    public DeliveryResult markFailed(Long outboxId, Exception cause) {
        NotificationOutbox outbox = outboxRepository.findById(outboxId).orElseThrow();
        RetryPolicy.Decision decision = retryPolicy.decide(outbox.getAttemptCount(), cause, LocalDateTime.now());

        if (decision.isDead()) {
            outbox.giveUp(decision.attempts(), cause.getMessage());
            historyRepository.save(NotificationHistory.builder()
                    .subscription(outbox.getSubscription()).status("FAIL").build());
        } else {
            outbox.scheduleRetry(decision.attempts(), decision.nextAttemptAt(), cause.getMessage());
        }
        return decision.result();
    }

    @Transactional(readOnly = true)
    public List<Long> findDueIds(int limit) {
        return outboxRepository.findDueIds(LocalDateTime.now(), PageRequest.of(0, limit));
    }

    /**
     * 구독 ID → 최신 발송 상태. 요청한 모든 ID 가 결과에 들어 있다.
     * Outbox 가 없는 구독은 Outbox 도입 전에 발송된 것이므로 DELIVERED 로 본다 (매칭된 구독을 조회하는 용도).
     */
    @Transactional(readOnly = true)
    public Map<Long, DeliveryStatus> findDeliveryStatuses(List<Long> subscriptionIds) {
        if (subscriptionIds.isEmpty()) return Map.of();
        Map<Long, DeliveryStatus> statuses = new HashMap<>();
        subscriptionIds.forEach(id -> statuses.put(id, DeliveryStatus.DELIVERED));
        for (var view : outboxRepository.findStatusesBySubscriptionIds(subscriptionIds)) {
            statuses.put(view.getSubscriptionId(), DeliveryStatus.of(view.getStatus()));   // outboxId 오름차순 → 마지막이 최신
        }
        return statuses;
    }

    public int releaseStaleLocks() {
        return outboxRepository.releaseStale(LocalDateTime.now().minus(properties.staleLock()));
    }


    /** 발송에 필요한 값만 담은 스냅샷 (트랜잭션 밖으로 엔티티를 넘기지 않기 위함) */
    public record OutboxMessage(Long outboxId, String chatId, String message) {
    }
}
