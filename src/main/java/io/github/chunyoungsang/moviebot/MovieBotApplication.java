package io.github.chunyoungsang.moviebot;

import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication   // 이 패키지(io.github.chunyoungsang.moviebot) 아래를 컴포넌트·엔티티·리포지토리 스캔
@EnableScheduling
public class MovieBotApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MovieBotApplication.class);
        app.addInitializers(new DotenvApplicationInitializer()); // 로컬 직접 실행 시 .env 자동 로드
        app.run(args);
    }
}