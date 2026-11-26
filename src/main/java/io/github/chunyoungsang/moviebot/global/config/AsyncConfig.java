package io.github.chunyoungsang.moviebot.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 텔레그램 알림 발송 전용 스레드풀.
     * 구독자가 늘어도 병렬로 발송 — 메인 스케줄러 스레드 블로킹 없음.
     */
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);      // 평시 상주 스레드
        executor.setMaxPoolSize(20);      // 최대 동시 발송 스레드
        executor.setQueueCapacity(200);   // 대기열 (초과 시 reject)
        executor.setThreadNamePrefix("notify-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * Outbox relay 발송 전용 스레드풀. relay 가 한 묶음을 나눠 보낸다.
     * 즉시 발송 풀(notificationExecutor)과 나눈 이유: 그 풀이 가득 차서 넘친 건을 relay 가 맡으므로
     * 같은 풀을 쓰면 relay 도 거부당한다. 크기는 텔레그램 응답 시간 × 초당 한도(25) 정도면 충분하다.
     */
    @Bean(name = "outboxRelayExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor outboxRelayExecutor(
            @Value("${notification.outbox.relay-concurrency:8}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Integer.MAX_VALUE);   // relay 가 한 묶음(relay-batch-size)씩만 넣고 끝나기를 기다린다
        executor.setThreadNamePrefix("relay-");
        executor.initialize();
        return executor;
    }

    /**
     * 크롤링 전용 스레드풀. 크기 = 동시에 크롤링할 그룹 수.
     * - core = max 이고 유휴 스레드를 없애지 않는다: 크롤러가 스레드마다 브라우저 세션을 붙여 재사용하기 때문
     * - 크기가 곧 동시에 띄우는 브라우저 수(스레드당 수백 MB)이므로 작게 유지한다
     */
    @Bean(name = "crawlExecutor")
    public ThreadPoolTaskExecutor crawlExecutor(@Value("${movie.crawl.concurrency:3}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Integer.MAX_VALUE);   // 그룹 수만큼 쌓일 수 있음 (5분마다 한 번, 수십~수백 건)
        executor.setAllowCoreThreadTimeOut(false);
        executor.setThreadNamePrefix("crawl-");
        executor.initialize();
        return executor;
    }
}
