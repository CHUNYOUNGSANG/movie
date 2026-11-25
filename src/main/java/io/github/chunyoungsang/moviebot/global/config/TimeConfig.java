package io.github.chunyoungsang.moviebot.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * "오늘"의 기준. 상영일(CGV)은 한국 시간 기준 날짜이므로 서버 시간대(TZ)와 무관하게 Asia/Seoul 로 고정한다.
 * 테스트에서는 고정 시각의 Clock 을 넣어 날짜 경계를 검증한다.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SEOUL);
    }
}
