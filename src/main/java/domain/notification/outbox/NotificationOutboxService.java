package domain.notification.outbox;

import domain.notification.entity.NotificationHistory;
import domain.notification.entity.NotificationSubscription;
import domain.notification.repository.NotificationHistoryRepository;
import domain.telegram.service.MessageSendException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
public class NotificationOutboxService {

    /** 발송 중(SENDING) 상태로 이 시간 이상 멈춰 있으면 서버 다운으로 보고 되돌린다. */
    private static final Duration STALE_LOCK = Duration.ofMinutes(5);
    private static final int ERROR_MAX_LENGTH = 500;

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationHistoryRepository historyRepository;

    /** 최대 재시도 간격 (지수 백오프 상한) */
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    @Value("${notification.outbox.base-delay-ms:30000}")
    private long baseDelayMs;

    @Value("${notification.outbox.max-attempts:5}")
    private int maxAttempts;

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
     * 발송 실패 처리.
     * - 영구 실패(재시도 불가)이거나 최대 시도 횟수에 도달하면 DEAD + FAIL 이력
     * - 그 외에는 지수 백오프(base × 2^(n-1), 최대 1시간) 후 다시 PENDING
     */
    public void markFailed(Long outboxId, Exception cause) {
        NotificationOutbox outbox = outboxRepository.findById(outboxId).orElseThrow();
        int attempts = outbox.getAttemptCount() + 1;
        outbox.setAttemptCount(attempts);
        outbox.setLastError(truncate(cause.getMessage()));
        outbox.setLockedAt(null);

        boolean retryable = !(cause instanceof MessageSendException mse) || mse.isRetryable();
        if (!retryable || attempts >= maxAttempts) {
            outbox.setStatus(OutboxStatus.DEAD);
            historyRepository.save(NotificationHistory.builder()
                    .subscription(outbox.getSubscription()).status("FAIL").build());
            return;
        }
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setNextAttemptAt(LocalDateTime.now().plus(backoff(attempts)));
    }

    /** attempts 회 실패 후 다음 시도까지의 대기 시간 */
    Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 20);   // 오버플로 방지
        Duration delay = Duration.ofMillis(baseDelayMs).multipliedBy(multiplier);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
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
        return outboxRepository.releaseStale(LocalDateTime.now().minus(STALE_LOCK));
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= ERROR_MAX_LENGTH ? s : s.substring(0, ERROR_MAX_LENGTH);
    }

    /** 발송에 필요한 값만 담은 스냅샷 (트랜잭션 밖으로 엔티티를 넘기지 않기 위함) */
    public record OutboxMessage(Long outboxId, String chatId, String message) {
    }
}
