package io.github.chunyoungsang.moviebot.telegram.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 텔레그램 Bot API 호출 속도 제한 (서버 1대 기준).
 *
 * - 전역 제한(초당 약 30건)보다 낮게 잡은 간격으로 호출을 고르게 분산한다. (기본 초당 25건 → 40ms 간격)
 *   한꺼번에 보내고 쉬는 방식이 아니라 일정 간격으로 내보내 순간 몰림(burst)을 만들지 않는다.
 * - 429 를 받으면 retry_after 동안 모든 호출을 멈춘다. 텔레그램은 이 시간 동안 들어온 요청을 모두 거부하고
 *   제한 시간을 늘리므로(flood control), 다른 스레드가 계속 보내면 오히려 회복이 늦어진다.
 */
@Component
public class TelegramRateLimiter {

    private final long intervalNanos;
    private long nextSlotNanos = System.nanoTime();
    private long pausedUntilNanos = 0;

    public TelegramRateLimiter(@Value("${telegram.rate-limit.per-second:25}") int perSecond) {
        if (perSecond <= 0) throw new IllegalArgumentException("telegram.rate-limit.per-second 는 1 이상이어야 합니다");
        this.intervalNanos = 1_000_000_000L / perSecond;
    }

    /**
     * 다음 호출 차례까지 대기한다. 잠금은 차례 예약에만 쓰고, 대기는 잠금 밖에서 한다.
     * 대기하는 동안 멈춤(pause)이 걸리면 멈춤이 끝난 뒤로 다시 줄을 선다.
     */
    public void acquire() throws InterruptedException {
        while (true) {
            long waitNanos;
            boolean reserved;
            synchronized (this) {
                long now = System.nanoTime();
                if (now - pausedUntilNanos < 0) {
                    waitNanos = pausedUntilNanos - now;   // 멈춤 중: 끝날 때까지 기다린 뒤 다시 줄을 선다
                    reserved = false;
                } else {
                    long slot = Math.max(now, nextSlotNanos);
                    nextSlotNanos = slot + intervalNanos;  // 내 차례 예약
                    waitNanos = slot - now;
                    reserved = true;
                }
            }
            if (waitNanos > 0) sleepNanos(waitNanos);
            if (!reserved) continue;
            synchronized (this) {
                if (System.nanoTime() - pausedUntilNanos >= 0) return;   // 기다리는 동안 멈춤이 없었으면 출발
            }
        }
    }

    /** 429 응답 시 호출: retryAfter 동안 모든 호출을 멈춘다 (이미 더 긴 멈춤이 걸려 있으면 유지) */
    public synchronized void pause(Duration retryAfter) {
        long until = System.nanoTime() + retryAfter.toNanos();
        if (until - pausedUntilNanos > 0) pausedUntilNanos = until;
        if (nextSlotNanos - pausedUntilNanos < 0) nextSlotNanos = pausedUntilNanos;   // 멈춤이 끝난 뒤부터 다시 간격 적용
    }

    private static void sleepNanos(long nanos) throws InterruptedException {
        Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
    }
}
