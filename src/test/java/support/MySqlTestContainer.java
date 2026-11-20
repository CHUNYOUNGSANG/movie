package support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 통합 테스트용 MySQL 8.4 컨테이너 (운영과 같은 버전).
 *
 * 컨테이너는 JVM 당 1개만 띄우고(시작 비용 절감), 테스트 클래스마다 새 database 를 만들어 격리한다.
 * 클래스별 스프링 컨텍스트는 캐시되어 스케줄러(Outbox relay 등)가 계속 돌기 때문에,
 * DB를 공유하면 다른 테스트 클래스의 데이터를 건드릴 수 있다.
 */
public final class MySqlTestContainer {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();   // JVM 종료 시 Testcontainers(Ryuk)가 정리
    }

    private MySqlTestContainer() {
    }

    public static void registerFreshDatabase(DynamicPropertyRegistry registry) {
        String database = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 DB 생성 실패", e);
        }
        registry.add("spring.datasource.url", () -> jdbcUrl(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    private static String jdbcUrl(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true";
    }
}
