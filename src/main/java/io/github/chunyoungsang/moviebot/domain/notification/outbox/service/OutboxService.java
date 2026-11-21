package io.github.chunyoungsang.moviebot.domain.notification.outbox.service;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.NotificationOutbox;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.entity.OutboxStatus;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.repository.NotificationOutboxRepository;

import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationHistory;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.repository.NotificationHistoryRepository;
import lombok.RequiredArgsConstructor;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;
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

    private static final int ERROR_MAX_LENGTH = 500;

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationHistoryRepository historyRepository;
    private final OutboxProperties properties;
    private final RetryPolicy retryPolicy;

    /** 매칭 트랜잭션 안에서 호출된다 (구독 상태 변경과 함께 커밋/롤백). */
    public NotificationOutbox enqueue(NotificationSubscription sub, String message) {
        return outboxRepository.save(NotificationOutbox.builder()
                .subscription(sub)
                .chatId(sub.getUser().getChatId())
                .message(message)
                .nextAttemptAt(LocalDateTime.now())
                .build());
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
        outbox.setStatus(OutboxStatus.SENT);
        outbox.setAttemptCount(outbox.getAttemptCount() + 1);
        outbox.setLockedAt(null);
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

        outbox.setAttemptCount(decision.attempts());
        outbox.setLastError(truncate(cause.getMessage()));
        outbox.setLockedAt(null);
        if (decision.isDead()) {
            outbox.setStatus(OutboxStatus.DEAD);
            historyRepository.save(NotificationHistory.builder()
                    .subscription(outbox.getSubscription()).status("FAIL").build());
        } else {
            outbox.setStatus(OutboxStatus.PENDING);
            outbox.setNextAttemptAt(decision.nextAttemptAt());
        }
        return decision.result();
    }

    @Transactional(readOnly = true)
    public List<Long> findDueIds(int limit) {
        return outboxRepository.findDueIds(LocalDateTime.now(), PageRequest.of(0, limit));
    }

    /** 구독 ID → 최신 발송 상태. Outbox 가 없는 구독(Outbox 도입 전 발송분)은 포함되지 않는다. */
    @Transactional(readOnly = true)
    public Map<Long, OutboxStatus> findLatestStatuses(List<Long> subscriptionIds) {
        if (subscriptionIds.isEmpty()) return Map.of();
        Map<Long, OutboxStatus> statuses = new HashMap<>();
        for (var view : outboxRepository.findStatusesBySubscriptionIds(subscriptionIds)) {
            statuses.put(view.getSubscriptionId(), view.getStatus());   // outboxId 오름차순 → 마지막이 최신
        }
        return statuses;
    }

    public int releaseStaleLocks() {
        return outboxRepository.releaseStale(LocalDateTime.now().minus(properties.staleLock()));
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= ERROR_MAX_LENGTH ? s : s.substring(0, ERROR_MAX_LENGTH);
    }

    /** 발송에 필요한 값만 담은 스냅샷 (트랜잭션 밖으로 엔티티를 넘기지 않기 위함) */
    public record OutboxMessage(Long outboxId, String chatId, String message) {
    }
}
