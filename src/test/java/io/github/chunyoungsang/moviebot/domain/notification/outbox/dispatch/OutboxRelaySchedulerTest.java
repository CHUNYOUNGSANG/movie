package io.github.chunyoungsang.moviebot.domain.notification.outbox.dispatch;

import io.github.chunyoungsang.moviebot.domain.notification.outbox.config.OutboxProperties;
import io.github.chunyoungsang.moviebot.domain.notification.outbox.service.OutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * relay 처리량 재현 (부하 테스트에서 발견).
 * 알림 5,000건 중 즉시 발송 스레드풀을 넘친 약 3,900건이 relay 로 넘어왔는데,
 * relay 가 "10초마다 50건, 한 스레드에서 차례로" 보내 초당 약 3.3건(허용치 25건의 13%)으로 묶였다.
 */
@DisplayName("OutboxRelayScheduler")
class OutboxRelaySchedulerTest {

    private static final int BATCH = 50;

    private final OutboxService outboxService = mock(OutboxService.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final ExecutorService relayExecutor = Executors.newFixedThreadPool(5);
    private final Set<Long> dispatched = ConcurrentHashMap.newKeySet();

    @AfterEach
    void tearDown() {
        relayExecutor.shutdownNow();
    }

    private OutboxRelayScheduler relay(Duration maxRunTime) {
        OutboxProperties props = new OutboxProperties(30000, Duration.ofHours(1), 5, 10000, BATCH, Duration.ofMinutes(5),
                maxRunTime);
        return new OutboxRelayScheduler(outboxService, dispatcher, props, relayExecutor);
    }

    private static List<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    @Test
    @DisplayName("발송할 건이 한 묶음보다 많으면 다음 실행(10초 뒤)을 기다리지 않고 이어서 보낸다")
    void drainsAllDueBatchesInOneRun() {
        given(outboxService.findDueIds(anyInt()))
                .willReturn(ids(1, 50), ids(51, 100), ids(101, 120), List.of());
        willAnswer(inv -> dispatched.add(inv.getArgument(0))).given(dispatcher).dispatch(anyLong());

        relay(Duration.ofMinutes(1)).relay();

        assertThat(dispatched).hasSize(120);
    }

    @Test
    @DisplayName("한 묶음 안의 발송을 여러 스레드로 나눠 보낸다 (차례로 보내지 않는다)")
    void dispatchesBatchInParallel() {
        given(outboxService.findDueIds(anyInt())).willReturn(ids(1, 10), List.of());
        willAnswer(inv -> {
            Thread.sleep(100);   // 발송 한 건에 0.1초
            return dispatched.add(inv.getArgument(0));
        }).given(dispatcher).dispatch(anyLong());

        long start = System.nanoTime();
        relay(Duration.ofMinutes(1)).relay();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(dispatched).hasSize(10);
        assertThat(elapsedMs).isLessThan(600);   // 차례로 보내면 1,000ms. 5개 스레드면 약 200ms
    }

    @Test
    @DisplayName("한 번 실행할 수 있는 시간을 넘기면 남은 건은 다음 실행으로 넘긴다")
    void stopsAfterMaxRunTime() {
        given(outboxService.findDueIds(anyInt())).willAnswer(inv -> ids(1, BATCH));   // 끝없이 남아 있는 상황
        willAnswer(inv -> {
            Thread.sleep(20);
            return null;
        }).given(dispatcher).dispatch(anyLong());

        long start = System.nanoTime();
        relay(Duration.ofMillis(300)).relay();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(1_500);
    }

    @Test
    @DisplayName("발송할 건이 없으면 아무것도 보내지 않는다")
    void nothingDue() {
        given(outboxService.findDueIds(anyInt())).willReturn(List.of());

        relay(Duration.ofMinutes(1)).relay();

        verify(dispatcher, never()).dispatch(anyLong());
    }
}
