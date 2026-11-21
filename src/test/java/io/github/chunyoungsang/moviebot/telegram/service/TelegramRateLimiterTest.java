package io.github.chunyoungsang.moviebot.telegram.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TelegramRateLimiter")
class TelegramRateLimiterTest {

    @Test
    @DisplayName("초당 N건이면 호출 간격이 1/N초로 고르게 벌어진다")
    void spacesCallsEvenly() throws InterruptedException {
        TelegramRateLimiter limiter = new TelegramRateLimiter(50);   // 20ms 간격

        long start = System.nanoTime();
        for (int i = 0; i < 11; i++) limiter.acquire();              // 첫 호출은 즉시, 이후 10번 대기
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(190);           // 10 × 20ms (타이머 오차 감안)
    }

    @Test
    @DisplayName("pause 하면 그 시간 동안 다음 호출이 대기한다")
    void pauseBlocksCalls() throws InterruptedException {
        TelegramRateLimiter limiter = new TelegramRateLimiter(1000);
        limiter.pause(Duration.ofMillis(300));

        long start = System.nanoTime();
        limiter.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(290);
    }

    @Test
    @DisplayName("더 짧은 pause 가 와도 이미 걸린 긴 멈춤은 줄어들지 않는다")
    void shorterPauseDoesNotShortenLongerPause() throws InterruptedException {
        TelegramRateLimiter limiter = new TelegramRateLimiter(1000);
        limiter.pause(Duration.ofMillis(300));
        limiter.pause(Duration.ofMillis(10));

        long start = System.nanoTime();
        limiter.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(290);
    }

    @Test
    @DisplayName("초당 건수가 0 이하면 생성할 수 없다")
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> new TelegramRateLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
