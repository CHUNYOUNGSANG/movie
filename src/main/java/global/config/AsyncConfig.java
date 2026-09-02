package global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
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
}
