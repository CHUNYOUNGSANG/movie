package io.github.chunyoungsang.moviebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 로컬 실행용 진입점: ./gradlew bootTestRun
 * MySQL 8.4 컨테이너를 띄우고 접속 정보(@ServiceConnection)를 자동으로 주입한 뒤 앱을 실행한다.
 * 운영과 같은 DB라 MySQL 전용 문법(행 별칭 upsert 등)과 Flyway 마이그레이션을 그대로 확인할 수 있다.
 * 컨테이너는 앱 종료 시 함께 정리된다 (데이터는 유지되지 않음).
 */
public class TestMovieBotApplication {

    public static void main(String[] args) {
        // 운영 진입점(MovieBotApplication.main: .env 로드 포함)을 그대로 쓰고 MySQL 컨테이너 설정만 더한다
        SpringApplication.from(MovieBotApplication::main).with(LocalMySqlContainer.class).run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalMySqlContainer {

        @Bean
        @ServiceConnection
        MySQLContainer mysql() {
            return new MySQLContainer("mysql:8.4")
                    .withDatabaseName("moviebotdb")
                    .withUrlParam("serverTimezone", "Asia/Seoul")
                    .withUrlParam("rewriteBatchedStatements", "true");
        }
    }
}
