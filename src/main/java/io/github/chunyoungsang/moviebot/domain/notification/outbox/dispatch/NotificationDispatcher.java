package io.github.chunyoungsang.moviebot.domain.notification.outbox.dispatch;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.DeliveryResult;

import io.github.chunyoungsang.moviebot.domain.notification.sender.MessageSender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbox 한 건을 발송한다. 선점(claim) → 발송(트랜잭션 밖) → 결과 기록 순서.
 * AFTER_COMMIT 리스너(즉시 발송)와 relay 스케줄러(누락분/재시도) 양쪽에서 호출된다.
 *
 * 지표 notification.delivery{result}: 발송 시도 한 번의 소요 시간과 결과 (sent / retry / rate_limited / dead / canceled)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final OutboxService outboxService;
    private final MessageSender messageSender;
    private final MeterRegistry meterRegistry;

    public void dispatch(Long outboxId) {
        OutboxService.OutboxMessage msg = outboxService.claim(outboxId).orElse(null);
        if (msg == null) return;   // 다른 쪽에서 이미 처리 중이거나 아직 차례가 아님

        if (!msg.recipientActive()) {   // 매칭 이후(재시도 대기 중 등) 구독을 해제함
            outboxService.cancel(outboxId, "받는 사람이 구독하지 않음");
            deliveryTimer(DeliveryResult.CANCELED).record(Duration.ZERO);   // 발송 시도 없음, 횟수만
            log.info("알림 발송 취소 (구독 해제): outboxId={}, chatId={}", outboxId, msg.chatId());
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            messageSender.deliver(msg.chatId(), msg.message());
        } catch (Exception e) {
            log.warn("알림 발송 실패: outboxId={}, chatId={}, cause={}", outboxId, msg.chatId(), e.getMessage());
            DeliveryResult result = outboxService.markFailed(outboxId, e);
            sample.stop(deliveryTimer(result));
            return;
        }
        sample.stop(deliveryTimer(DeliveryResult.SENT));
        outboxService.markSent(outboxId);
        log.info("알림 발송 완료: outboxId={}, chatId={}", outboxId, msg.chatId());
    }

    private Timer deliveryTimer(DeliveryResult result) {
        return Timer.builder("notification.delivery")
                .description("텔레그램 발송 시도 (결과별 횟수와 소요 시간)")
                .tag("result", result == null ? "unknown" : result.tag())
                .register(meterRegistry);
    }
}
