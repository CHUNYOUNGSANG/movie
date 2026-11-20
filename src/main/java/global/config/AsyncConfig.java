package global.config;

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
