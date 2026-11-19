import domain.TestApplication;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
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
        // MovieBotApplication 은 domain 패키지를 스캔하므로 테스트 클래스패스의 domain.TestApplication 까지 읽어
        // 빈이 중복 등록된다. 같은 설정(스캔 범위, 스케줄링)인 TestApplication 으로 띄운다.
        SpringApplication app = new SpringApplication(TestApplication.class, LocalMySqlContainer.class);
        app.addInitializers(new DotenvApplicationInitializer());   // MovieBotApplication 과 동일하게 .env 로드
        app.run(args);
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
