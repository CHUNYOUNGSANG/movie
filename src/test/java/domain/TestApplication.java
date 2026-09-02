package domain;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"domain", "global"})
@EnableScheduling
@EntityScan(basePackages = "domain")
@EnableJpaRepositories(basePackages = "domain")
public class TestApplication {
}