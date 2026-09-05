import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"domain", "global"})
@EnableScheduling
@EntityScan(basePackages = "domain")
@EnableJpaRepositories(basePackages = "domain")
public class MovieBotApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MovieBotApplication.class);
        app.addInitializers(new DotenvApplicationInitializer()); // 로컬 직접 실행 시 .env 자동 로드
        app.run(args);
    }
}